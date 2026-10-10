package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.dto.PdfDocumentParseResult;
import org.example.dto.PdfDocumentParseResult.BoundingBox;
import org.example.dto.PdfDocumentParseResult.ImageAsset;
import org.example.dto.PdfDocumentParseResult.TextChunk;
import org.example.observability.AgentTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private PdfDocumentParserService pdfParser;

    @Autowired
    private ObjectStorageService objectStorage;

    @Autowired
    private ImageSummarizationService imageSummarizationService;

    @Autowired
    private RetryExecutor retryExecutor;

    @Autowired
    private AgentTelemetry telemetry;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".pdf")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            indexPdf(path, file);
            return;
        }

        String content = Files.readString(path);

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并插入 Milvus
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            
            try {
                // 生成向量
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

                // 构建元数据（包含文件信息）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                // 插入到 Milvus
                insertToMilvus(chunk.getContent(), vector, metadata,
                        UUID.nameUUIDFromBytes((metadata.get("_source") + "_" + chunk.getChunkIndex())
                                .getBytes()).toString());
                
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }
    private void indexPdf(Path path, File file) throws Exception {
        String source = normalizeSource(path);
        PdfDocumentParseResult parsed = telemetry.observe("pdf.parse", () -> pdfParser.parse(file, source));
        deleteExistingData(source);

        String originalObjectKey = null;
        if (objectStorage.isReady()) {
            try {
                objectStorage.clearDocument(parsed.getDocumentId());
                originalObjectKey = retryExecutor.execute("minio-put-original", () ->
                        objectStorage.putOriginalPdf(parsed.getDocumentId(), Files.readAllBytes(path)));
            } catch (Exception error) {
                logger.warn("PDF 原文件写入 MinIO 失败，继续建立文本索引: {}", error.getMessage());
            }
        }
        final String storedOriginalObjectKey = originalObjectKey;

        int indexedText = 0;
        for (TextChunk chunk : parsed.getTextChunks()) {
            try {
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = baseMetadata(source, file.getName(), ".pdf");
                metadata.put("documentId", parsed.getDocumentId());
                metadata.put("contentType", "pdf_text");
                metadata.put("chunkId", chunk.getChunkId());
                metadata.put("chunkIndex", chunk.getSequence());
                metadata.put("page", chunk.getPage());
                metadata.put("pageCount", parsed.getPageCount());
                metadata.put("sectionId", chunk.getSectionId());
                metadata.put("boundingBox", boundingBox(chunk.getBoundingBox()));
                metadata.put("relatedImageIds", chunk.getRelatedImageIds());
                if (storedOriginalObjectKey != null) {
                    metadata.put("minioBucket", objectStorage.bucket());
                    metadata.put("originalPdfObjectKey", storedOriginalObjectKey);
                }
                telemetry.observe("milvus.insert", () -> {
                    insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkId());
                    return null;
                });
                indexedText++;
            } catch (Exception error) {
                throw new RuntimeException("PDF 文本分片索引失败: " + chunk.getChunkId(), error);
            }
        }

        int indexedImages = 0;
        for (ImageAsset image : parsed.getImages()) {
            if (!objectStorage.isReady()) {
                logger.warn("跳过图片索引，MinIO 当前不可用，imageId={}", image.getImageId());
                continue;
            }
            try {
                String objectKey = retryExecutor.execute("minio-put-image", () ->
                        objectStorage.putImage(parsed.getDocumentId(), image.getImageId(), image.getPngData()));
                String summary = retryExecutor.execute("dashscope-vision", () ->
                        imageSummarizationService.summarize(image));
                String indexedContent = "PDF第" + image.getPage() + "页图片摘要：" + summary;
                List<Float> vector = embeddingService.generateEmbedding(indexedContent);
                Map<String, Object> metadata = baseMetadata(source, file.getName(), ".pdf");
                metadata.put("documentId", parsed.getDocumentId());
                metadata.put("contentType", "pdf_image");
                metadata.put("chunkId", image.getImageId());
                metadata.put("imageId", image.getImageId());
                metadata.put("page", image.getPage());
                metadata.put("pageCount", parsed.getPageCount());
                metadata.put("sectionId", image.getSectionId());
                metadata.put("boundingBox", boundingBox(image.getBoundingBox()));
                metadata.put("relatedChunkIds", image.getRelatedChunkIds());
                metadata.put("minioBucket", objectStorage.bucket());
                metadata.put("minioObjectKey", objectKey);
                if (storedOriginalObjectKey != null) metadata.put("originalPdfObjectKey", storedOriginalObjectKey);
                metadata.put("imageSha256", image.getSha256());
                metadata.put("pixelWidth", image.getPixelWidth());
                metadata.put("pixelHeight", image.getPixelHeight());
                telemetry.observe("milvus.insert", () -> {
                    insertToMilvus(indexedContent, vector, metadata, image.getImageId());
                    return null;
                });
                indexedImages++;
            } catch (Exception error) {
                logger.warn("图片处理失败，不影响 PDF 文本索引，imageId={}, reason={}",
                        image.getImageId(), error.getMessage());
            }
        }
        logger.info("PDF 索引完成，pages={}, textChunks={}, extractedImages={}, indexedImages={}",
                parsed.getPageCount(), indexedText, parsed.getImages().size(), indexedImages);
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     */
    private void deleteExistingData(String filePath) {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            
            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Path path = Paths.get(filePath).normalize();
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        Map<String, Object> metadata = baseMetadata(normalizeSource(path), fileNameStr, extension);
        metadata.put("contentType", "text");
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    private Map<String, Object> baseMetadata(String source, String fileName, String extension) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", source);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileName);
        return metadata;
    }

    private Map<String, Object> boundingBox(BoundingBox box) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("x", box.x());
        value.put("y", box.y());
        value.put("width", box.width());
        value.put("height", box.height());
        value.put("origin", "top-left");
        return value;
    }

    private String normalizeSource(Path path) {
        return path.normalize().toString().replace(File.separator, "/");
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector,
                                Map<String, Object> metadata, String id) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            
            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            
            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, contentType={}", id, metadata.get("contentType"));

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
