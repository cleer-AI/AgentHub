package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import org.example.config.MinioProperties;
import org.example.observability.AgentTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Service
public class ObjectStorageService {
    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageService.class);

    private final MinioProperties properties;
    private final AgentTelemetry telemetry;
    private MinioClient client;
    private volatile boolean ready;

    public ObjectStorageService(MinioProperties properties, AgentTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) {
            logger.info("MinIO 图片存储已禁用");
            return;
        }
        try {
            client = MinioClient.builder()
                    .endpoint(properties.getEndpoint())
                    .credentials(properties.getAccessKey(), properties.getSecretKey())
                    .build();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            ready = true;
            logger.info("MinIO 对象存储初始化完成，bucket={}", properties.getBucket());
        } catch (Exception error) {
            ready = false;
            logger.warn("MinIO 初始化失败，PDF 文本仍可正常索引: {}", error.getMessage());
        }
    }

    public boolean isReady() { return ready; }
    public String bucket() { return properties.getBucket(); }

    public String putOriginalPdf(String documentId, byte[] data) {
        return put(documentId + "/original.pdf", data, "application/pdf");
    }

    public String putImage(String documentId, String imageId, byte[] data) {
        return put(documentId + "/images/" + imageId + ".png", data, "image/png");
    }

    public void clearDocument(String documentId) {
        if (!ready) return;
        telemetry.observe("minio.delete_prefix", () -> {
            Iterable<Result<Item>> objects = client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket()).prefix(documentId + "/").recursive(true).build());
            for (Result<Item> object : objects) {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.getBucket()).object(object.get().objectName()).build());
            }
            return null;
        });
    }

    private String put(String objectKey, byte[] data, String contentType) {
        if (!ready) throw new IllegalStateException("MinIO is not available");
        return telemetry.observe("minio.put", () -> {
            try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(objectKey)
                        .stream(input, data.length, -1)
                        .contentType(contentType)
                        .build());
            }
            return objectKey;
        });
    }
}
