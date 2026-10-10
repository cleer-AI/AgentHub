package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.example.observability.AgentTelemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);
    private static final int MAX_EXPANDED_RESULTS = 8;
    private static final int MAX_RELATIONS_PER_HIT = 2;
    private final Gson gson = new Gson();

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private RetryExecutor retryExecutor;

    @Autowired
    private AgentTelemetry telemetry;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return telemetry.observe("rag.retrieve", () ->
                retryExecutor.execute("milvus-search", () -> searchOnce(query, topK)));
    }

    private List<SearchResult> searchOnce(String query, int topK) {
        try {
            logger.info("开始搜索相似文档, queryLength={}, topK={}",
                    query == null ? 0 : query.length(), topK);

            // 1. 将查询文本向量化
            List<Float> queryVector = telemetry.observe("rag.embedding",
                    () -> embeddingService.generateQueryVector(query));
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = telemetry.observe("rag.milvus_search",
                    () -> milvusClient.search(searchParam));

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                
                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                
                results.add(result);
            }

            List<SearchResult> expanded = telemetry.observe("rag.relation_expand",
                    () -> expandRelatedResults(results));
            logger.info("搜索完成, directHits={}, expandedResults={}", results.size(), expanded.size());
            return expanded;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    private List<SearchResult> expandRelatedResults(List<SearchResult> directResults) {
        Map<String, SearchResult> combined = new LinkedHashMap<>();
        Set<String> relatedIds = new LinkedHashSet<>();
        for (SearchResult result : directResults) {
            result.setRelation("direct");
            combined.put(result.getId(), result);
            collectRelatedIds(result.getMetadata(), relatedIds);
        }
        relatedIds.removeAll(combined.keySet());
        if (relatedIds.isEmpty() || combined.size() >= MAX_EXPANDED_RESULTS) {
            return new ArrayList<>(combined.values());
        }

        List<String> limitedIds = relatedIds.stream()
                .limit(MAX_EXPANDED_RESULTS - combined.size())
                .toList();
        String expr = "id in [" + limitedIds.stream()
                .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((left, right) -> left + "," + right).orElse("") + "]";
        QueryParam query = QueryParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(List.of("id", "content", "metadata"))
                .withLimit((long) limitedIds.size())
                .build();
        R<QueryResults> response = milvusClient.query(query);
        if (response.getStatus() != 0) {
            logger.warn("RAG 关联扩展查询失败: {}", response.getMessage());
            return new ArrayList<>(combined.values());
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
            SearchResult result = new SearchResult();
            result.setId(String.valueOf(row.get("id")));
            result.setContent(String.valueOf(row.get("content")));
            Object metadata = row.get("metadata");
            result.setMetadata(metadata == null ? null : metadata.toString());
            result.setRelation("expanded");
            result.setScore(Float.NaN);
            combined.putIfAbsent(result.getId(), result);
        }
        return new ArrayList<>(combined.values());
    }

    private void collectRelatedIds(String metadata, Set<String> output) {
        if (metadata == null || metadata.isBlank()) return;
        try {
            JsonObject json = gson.fromJson(metadata, JsonObject.class);
            addIds(json.getAsJsonArray("relatedChunkIds"), output);
            addIds(json.getAsJsonArray("relatedImageIds"), output);
        } catch (Exception error) {
            logger.debug("忽略无法解析的关联元数据");
        }
    }

    private void addIds(JsonArray values, Set<String> output) {
        if (values == null) return;
        int count = 0;
        for (JsonElement value : values) {
            if (count++ >= MAX_RELATIONS_PER_HIT) break;
            if (value.isJsonPrimitive()) output.add(value.getAsString());
        }
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        private String relation;

    }
}
