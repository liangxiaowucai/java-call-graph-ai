package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.service.VectorStoreService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

@Service
public class QdrantVectorStoreImpl implements VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(QdrantVectorStoreImpl.class);
    private static final String FIELD_REPO_ID   = "repoId";
    private static final String FIELD_FULL_METHOD  = "fullMethod";
    private static final String FIELD_ENDPOINT_TYPE = "endpointType";
    private static final String FIELD_SUMMARY    = "summary";

    @Value("${platform.qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${platform.qdrant.collection:javacg2_chunks}")
    private String collectionName;

    private QdrantClient client;
    private volatile boolean available = false;
    private volatile long lastRetryTime = 0;
    private static final long RETRY_INTERVAL_MS = 30_000; // 30 秒重试间隔

    @PostConstruct
    public void init() {
        tryConnect();
    }

    private synchronized void tryConnect() {
        try {
            String url = qdrantUrl.replaceFirst("https?://", "");
            String host = url.contains(":") ? url.substring(0, url.lastIndexOf(':')) : url;

            client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(host, 6334, false).build());

            // 轻量健康检查
            client.listCollectionsAsync().get(5, TimeUnit.SECONDS);
            available = true;
            logger.info("[Qdrant] 连接成功: {}:6334", host);
        } catch (Exception e) {
            available = false;
            logger.warn("[Qdrant] 连接失败，向量检索将降级为关键词搜索: {}", e.getMessage());
        }
        lastRetryTime = System.currentTimeMillis();
    }

    @Override
    public boolean isAvailable() {
        if (available && client != null) return true;
        // 懒重连：如果距上次尝试超过 30 秒，重试一次
        if (System.currentTimeMillis() - lastRetryTime > RETRY_INTERVAL_MS) {
            logger.info("[Qdrant] 尝试重新连接...");
            tryConnect();
        }
        return available && client != null;
    }

    @Override
    public void ensureCollection(int dimensions) {
        if (!isAvailable()) return;
        try {
            List<String> collections = client.listCollectionsAsync().get(5, TimeUnit.SECONDS);
            if (!collections.contains(collectionName)) {
                client.createCollectionAsync(collectionName,
                        VectorParams.newBuilder()
                                .setSize(dimensions)
                                .setDistance(Distance.Cosine)
                                .build())
                        .get(10, TimeUnit.SECONDS);
                logger.info("[Qdrant] 创建 collection: {}, dimensions={}", collectionName, dimensions);
            }
        } catch (Exception e) {
            logger.error("[Qdrant] ensureCollection 失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        if (!isAvailable() || points == null || points.isEmpty()) return;
        try {
            List<PointStruct> structs = new ArrayList<>(points.size());
            for (VectorPoint p : points) {
                // 构建 float 数组 → VectorsFactory.vectors()
                float[] vec = p.vector();
                PointStruct struct = PointStruct.newBuilder()
                        .setId(id(java.util.UUID.fromString(p.id())))
                        .setVectors(vectors(vec))
                        .putPayload(FIELD_REPO_ID,       value(p.repoId()))
                        .putPayload(FIELD_FULL_METHOD,   value(p.fullMethod() != null ? p.fullMethod() : ""))
                        .putPayload(FIELD_ENDPOINT_TYPE, value(p.endpointType() != null ? p.endpointType() : ""))
                        .putPayload(FIELD_SUMMARY,       value(p.summary() != null ? p.summary() : ""))
                        .build();
                structs.add(struct);
            }

            client.upsertAsync(collectionName, structs)
                    .get(30, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("[Qdrant] upsert 失败 ({} 个点): {}", points.size(), e.getMessage(), e);
            throw new RuntimeException("Qdrant upsert 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, List<Long> repoIds, int topK) {
        if (!isAvailable() || queryVector == null || queryVector.length == 0) return List.of();
        try {
            // 构建 float list
            List<Float> queryList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) queryList.add(v);

            // 构建 repoId 过滤：多个 repoId 用 should（OR）
            SearchPoints.Builder builder = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryList)
                    .setLimit(topK)
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder()
                            .setEnable(true).build());

            if (repoIds != null && !repoIds.isEmpty()) {
                Filter.Builder filterBuilder = Filter.newBuilder();
                for (Long repoId : repoIds) {
                    filterBuilder.addShould(
                            io.qdrant.client.grpc.Points.Condition.newBuilder()
                                    .setField(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                            .setKey(FIELD_REPO_ID)
                                            .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                                    .setInteger(repoId)
                                                    .build())
                                            .build())
                                    .build());
                }
                builder.setFilter(filterBuilder.build());
            }

            List<ScoredPoint> scored = client.searchAsync(builder.build())
                    .get(10, TimeUnit.SECONDS);

            List<SearchResult> results = new ArrayList<>(scored.size());
            for (ScoredPoint sp : scored) {
                var payload = sp.getPayloadMap();
                String fullMethod   = getStr(payload, FIELD_FULL_METHOD);
                long   repoId       = getLong(payload, FIELD_REPO_ID);
                String endpointType = getStr(payload, FIELD_ENDPOINT_TYPE);
                String summary      = getStr(payload, FIELD_SUMMARY);
                results.add(new SearchResult(fullMethod, repoId, sp.getScore(), endpointType, summary));
            }
            return results;

        } catch (Exception e) {
            logger.error("[Qdrant] search 失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public void deleteByRepoId(Long repoId) {
        if (!isAvailable()) return;
        try {
            Filter filter = Filter.newBuilder()
                    .addMust(io.qdrant.client.grpc.Points.Condition.newBuilder()
                            .setField(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                    .setKey(FIELD_REPO_ID)
                                    .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                            .setInteger(repoId)
                                            .build())
                                    .build())
                            .build())
                    .build();

            client.deleteAsync(collectionName, filter, Duration.ofSeconds(30))
                    .get(35, TimeUnit.SECONDS);
            logger.info("[Qdrant] 删除仓库 {} 的向量点", repoId);
        } catch (Exception e) {
            logger.error("[Qdrant] deleteByRepoId 失败 repoId={}: {}", repoId, e.getMessage(), e);
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private String getStr(java.util.Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        return payload.containsKey(key) ? payload.get(key).getStringValue() : "";
    }

    private long getLong(java.util.Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        return payload.containsKey(key) ? payload.get(key).getIntegerValue() : 0L;
    }
}
