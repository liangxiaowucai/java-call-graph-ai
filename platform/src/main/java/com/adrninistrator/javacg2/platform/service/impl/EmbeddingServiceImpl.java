package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import com.adrninistrator.javacg2.platform.service.EmbeddingService;
import com.adrninistrator.javacg2.platform.service.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

    private final ChunkRepo chunkRepo;
    private final VectorStoreService vectorStoreService;
    private final SystemConfigRepo systemConfigRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 全局单例锁：同一时间只允许一个仓库的向量建设任务运行 */
    private final AtomicBoolean embeddingRunning = new AtomicBoolean(false);
    /** 当前正在建设的仓库 ID（用于日志前端轮询） */
    private volatile Long currentEmbeddingRepoId = null;

    @Value("${platform.embedding.api-url:https://api.openai.com}")
    private String apiUrl;

    @Value("${platform.embedding.api-key:}")
    private String apiKey;

    @Value("${platform.embedding.model:text-embedding-3-large}")
    private String model;

    @Value("${platform.embedding.batch-size:100}")
    private int batchSize;

    @Value("${platform.embedding.dimensions:3072}")
    private int dimensions;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public EmbeddingServiceImpl(ChunkRepo chunkRepo, VectorStoreService vectorStoreService,
                                 SystemConfigRepo systemConfigRepo) {
        this.chunkRepo = chunkRepo;
        this.vectorStoreService = vectorStoreService;
        this.systemConfigRepo = systemConfigRepo;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        if (!isConfigured()) {
            logger.warn("[Embedding] 未配置 API Key 或 API 地址，跳过 embedding（向量检索为选配功能）");
            return List.of();
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode inputArray = body.putArray("input");
            for (String text : texts) {
                // 截断超长文本（OpenAI text-embedding-3-large 最大 8191 token，~32k 字符）
                String truncated = text != null && text.length() > 8000 ? text.substring(0, 8000) : text;
                inputArray.add(truncated != null ? truncated : "");
            }

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveApiUrl() + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resolveApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("[Embedding] API 返回错误: status={}, body={}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            List<float[]> results = new ArrayList<>(texts.size());
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.path("embedding");
                float[] vector = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                results.add(vector);
            }
            return results;

        } catch (Exception e) {
            logger.error("[Embedding] embedBatch 失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    @Async("embeddingExecutor")
    public void embedChunksBatchAsync(Long repoId) {
        // 注意：本方法是「调用链分析完成后」的后台自动触发任务。
        // 它绝不能写 buildLogService（那是调用链分析的构建日志流，按 repoId 共享），
        // 否则会 clear/覆盖调用链分析日志、把分析状态改成失败。这里只写应用日志(logger)。
        // 用户手动触发的向量建设走 embedChunksWithCallback，有独立的 SSE 回调通道。

        // ── 单例锁：同一时间只允许一个向量建设任务 ──────────────────────────
        if (!embeddingRunning.compareAndSet(false, true)) {
            logger.warn("[Embedding] 已有向量建设任务正在运行（仓库 {}），跳过 repoId={}", currentEmbeddingRepoId, repoId);
            return;
        }
        currentEmbeddingRepoId = repoId;

        try {
            logger.info("[Embedding] 开始为仓库 {} 生成向量（后台异步）", repoId);

            // Embedding 未配置（选配）→ 安静跳过
            if (!isConfigured()) {
                logger.info("[Embedding] 未配置 API Key/地址，跳过向量索引 repoId={}（语义搜索降级为关键词）", repoId);
                return;
            }

            // Qdrant 不可用 → 安静跳过
            if (!vectorStoreService.isAvailable()) {
                logger.warn("[Embedding] Qdrant 不可用，跳过向量生成 repoId={}", repoId);
                return;
            }
            vectorStoreService.ensureCollection(dimensions);

            // 删除该仓库旧向量（重建）
            vectorStoreService.deleteByRepoId(repoId);

            // 重置 embedding_status
            chunkRepo.resetEmbeddingStatus(repoId);

            List<ChunkEntity> allChunks = chunkRepo.findByRepoId(repoId);
            int total = allChunks.size();
            int done = 0, failed = 0;
            int totalBatches = (total + batchSize - 1) / batchSize;

            logger.info("[Embedding] 仓库 {} 共 {} 个方法待处理，分 {} 批执行", repoId, total, totalBatches);

            for (int i = 0; i < total; i += batchSize) {
                List<ChunkEntity> batch = allChunks.subList(i, Math.min(i + batchSize, total));

                // 构建 embed 文本：call_summary + full_method
                List<String> texts = new ArrayList<>(batch.size());
                for (ChunkEntity chunk : batch) {
                    StringBuilder sb = new StringBuilder();
                    if (chunk.getCallSummary() != null && !chunk.getCallSummary().isBlank()) {
                        sb.append(chunk.getCallSummary()).append(" ");
                    }
                    sb.append(chunk.getFullMethod());
                    texts.add(sb.toString());
                }

                List<float[]> vectors = embedBatch(texts);

                if (vectors.isEmpty()) {
                    List<Long> failedIds = batch.stream().map(ChunkEntity::getId).toList();
                    chunkRepo.updateEmbeddingStatus(failedIds, "FAILED");
                    failed += batch.size();
                    logger.warn("[Embedding] 批次 embedding 失败，repoId={}, batch start={}", repoId, i);
                    continue;
                }

                // 构建 VectorPoint 列表
                List<VectorStoreService.VectorPoint> points = new ArrayList<>(vectors.size());
                List<Long> doneIds = new ArrayList<>(vectors.size());
                for (int j = 0; j < vectors.size() && j < batch.size(); j++) {
                    ChunkEntity chunk = batch.get(j);
                    String pointId = UUID.nameUUIDFromBytes(
                            (repoId + ":" + chunk.getFullMethod()).getBytes()).toString();
                    points.add(new VectorStoreService.VectorPoint(
                            pointId, vectors.get(j), repoId, chunk.getFullMethod(),
                            chunk.getAnnotations(),
                            chunk.getCallSummary() != null ? chunk.getCallSummary() : ""));
                    doneIds.add(chunk.getId());
                }

                try {
                    vectorStoreService.upsert(points);
                    chunkRepo.updateEmbeddingStatus(doneIds, "DONE");
                    done += points.size();
                } catch (Exception e) {
                    chunkRepo.updateEmbeddingStatus(doneIds, "FAILED");
                    failed += doneIds.size();
                    logger.error("[Embedding] Qdrant upsert 失败 repoId={}: {}", repoId, e.getMessage());
                }

                // 每 10 批打一次进度日志
                int batchNum = i / batchSize + 1;
                if (batchNum % 10 == 0 || batchNum == totalBatches) {
                    logger.info("[Embedding] 进度 repoId={}: {}/{}", repoId, done + failed, total);
                }
            }

            logger.info("[Embedding] 完成 repoId={}: done={}, failed={}, total={}", repoId, done, failed, total);

        } catch (Exception e) {
            // 向量建设失败绝不能影响调用链分析结果，吞掉异常只记日志
            logger.error("[Embedding] 后台向量建设异常 repoId={}，不影响调用链分析: {}", repoId, e.getMessage(), e);
        } finally {
            embeddingRunning.set(false);
            currentEmbeddingRepoId = null;
        }
    }

    @Override
    public void embedChunksWithCallback(Long repoId, java.util.function.Consumer<String> logCallback) {
        // ── 单例锁：同一时间只允许一个向量建设任务 ──────────────────────────
        if (!embeddingRunning.compareAndSet(false, true)) {
            String msg = "⚠️ 已有向量建设任务正在运行（仓库 " + currentEmbeddingRepoId + "），请等待完成后再试";
            logger.warn("[Embedding] {}", msg);
            logCallback.accept(msg);
            return;
        }
        currentEmbeddingRepoId = repoId;

        try {
            logCallback.accept("🔢 开始向量建设 (repoId=" + repoId + ")");
            logger.info("[Embedding] 开始为仓库 {} 生成向量", repoId);

            if (!isConfigured()) {
                logCallback.accept("ℹ️ 未配置 Embedding（API Key / 地址），无法生成向量。请在「系统配置 → Embedding 配置」中填写 API 地址（含 https://）和 API Key。");
                return;
            }

            if (!vectorStoreService.isAvailable()) {
                String msg = "❌ Qdrant 不可用，请确认 Docker 已启动并运行 Qdrant";
                logger.warn("[Embedding] Qdrant 不可用，跳过向量生成 repoId={}", repoId);
                logCallback.accept(msg);
                return;
            }
            logCallback.accept("✅ Qdrant 连接正常");
            vectorStoreService.ensureCollection(dimensions);

            logCallback.accept("🗑️  清除旧向量数据...");
            vectorStoreService.deleteByRepoId(repoId);

            chunkRepo.resetEmbeddingStatus(repoId);

            List<ChunkEntity> allChunks = chunkRepo.findByRepoId(repoId);
            int total = allChunks.size();
            int done = 0, failed = 0;
            int totalBatches = (total + batchSize - 1) / batchSize;

            logCallback.accept("📦 共 " + total + " 个方法待处理，分 " + totalBatches + " 批执行");
            logger.info("[Embedding] 仓库 {} 共 {} 个方法待处理", repoId, total);

            for (int i = 0; i < total; i += batchSize) {
                List<ChunkEntity> batch = allChunks.subList(i, Math.min(i + batchSize, total));

                List<String> texts = new ArrayList<>(batch.size());
                for (ChunkEntity chunk : batch) {
                    StringBuilder sb = new StringBuilder();
                    if (chunk.getCallSummary() != null && !chunk.getCallSummary().isBlank()) {
                        sb.append(chunk.getCallSummary()).append(" ");
                    }
                    sb.append(chunk.getFullMethod());
                    texts.add(sb.toString());
                }

                List<float[]> vectors = embedBatch(texts);

                if (vectors.isEmpty()) {
                    List<Long> failedIds = batch.stream().map(ChunkEntity::getId).toList();
                    chunkRepo.updateEmbeddingStatus(failedIds, "FAILED");
                    failed += batch.size();
                    logCallback.accept("⚠️ 批次 embedding 失败 (batch start=" + i + ")");
                    logger.warn("[Embedding] 批次 embedding 失败，repoId={}, batch start={}", repoId, i);
                    continue;
                }

                List<VectorStoreService.VectorPoint> points = new ArrayList<>(vectors.size());
                List<Long> doneIds = new ArrayList<>(vectors.size());
                for (int j = 0; j < vectors.size() && j < batch.size(); j++) {
                    ChunkEntity chunk = batch.get(j);
                    String pointId = UUID.nameUUIDFromBytes(
                            (repoId + ":" + chunk.getFullMethod()).getBytes()).toString();
                    points.add(new VectorStoreService.VectorPoint(
                            pointId, vectors.get(j), repoId, chunk.getFullMethod(),
                            chunk.getAnnotations(),
                            chunk.getCallSummary() != null ? chunk.getCallSummary() : ""));
                    doneIds.add(chunk.getId());
                }

                try {
                    vectorStoreService.upsert(points);
                    chunkRepo.updateEmbeddingStatus(doneIds, "DONE");
                    done += points.size();
                } catch (Exception e) {
                    chunkRepo.updateEmbeddingStatus(doneIds, "FAILED");
                    failed += doneIds.size();
                    logCallback.accept("⚠️ Qdrant upsert 失败: " + e.getMessage());
                    logger.error("[Embedding] Qdrant upsert 失败 repoId={}: {}", repoId, e.getMessage());
                }

                int batchNum = i / batchSize + 1;
                // 每批都打进度日志，防止 SSE 连接超时断开
                {
                    int pct = total > 0 ? (done + failed) * 100 / total : 100;
                    logCallback.accept("⏳ 进度: " + (done + failed) + "/" + total + " (" + pct + "%) done=" + done + " failed=" + failed);
                    logger.info("[Embedding] 进度 repoId={}: {}/{}", repoId, done + failed, total);
                }
            }

            logCallback.accept("📊 向量建设完成: done=" + done + ", failed=" + failed + ", total=" + total);
            logger.info("[Embedding] 完成 repoId={}: done={}, failed={}, total={}", repoId, done, failed, total);

        } finally {
            embeddingRunning.set(false);
            currentEmbeddingRepoId = null;
        }
    }

    @Override
    public void continueEmbedding(Long repoId, java.util.function.Consumer<String> logCallback) {
        // ── 单例锁 ──────────────────────────
        if (!embeddingRunning.compareAndSet(false, true)) {
            String msg = "⚠️ 已有向量建设任务正在运行（仓库 " + currentEmbeddingRepoId + "），请等待完成后再试";
            logger.warn("[Embedding] {}", msg);
            logCallback.accept(msg);
            return;
        }
        currentEmbeddingRepoId = repoId;

        try {
            logCallback.accept("🔢 继续向量建设 (repoId=" + repoId + ")");
            logger.info("[Embedding] 继续为仓库 {} 生成向量（跳过已完成）", repoId);

            if (!isConfigured()) {
                logCallback.accept("ℹ️ 未配置 Embedding（API Key / 地址），无法生成向量。请在「系统配置 → Embedding 配置」中填写 API 地址（含 https://）和 API Key。");
                return;
            }

            if (!vectorStoreService.isAvailable()) {
                logCallback.accept("❌ Qdrant 不可用，请确认 Docker 已启动并运行 Qdrant");
                return;
            }
            logCallback.accept("✅ Qdrant 连接正常");
            vectorStoreService.ensureCollection(dimensions);

            // 只查询未完成的 chunks（不删旧向量）
            List<ChunkEntity> pendingChunks = chunkRepo.findPendingByRepoId(repoId);
            long totalAll = chunkRepo.countByRepoId(repoId);
            long alreadyDone = totalAll - pendingChunks.size();
            int total = pendingChunks.size();

            if (total == 0) {
                logCallback.accept("✅ 全部已完成（" + totalAll + " 个方法），无需继续");
                return;
            }

            logCallback.accept("📦 跳过已完成 " + alreadyDone + " 个，剩余 " + total + " 个待处理");
            logger.info("[Embedding] 仓库 {} 跳过已完成 {}，剩余 {} 待处理", repoId, alreadyDone, total);

            int done = 0, failed = 0;
            int totalBatches = (total + batchSize - 1) / batchSize;

            for (int i = 0; i < total; i += batchSize) {
                List<ChunkEntity> batch = pendingChunks.subList(i, Math.min(i + batchSize, total));

                List<String> texts = new ArrayList<>(batch.size());
                for (ChunkEntity chunk : batch) {
                    StringBuilder sb = new StringBuilder();
                    if (chunk.getCallSummary() != null && !chunk.getCallSummary().isBlank()) {
                        sb.append(chunk.getCallSummary()).append(" ");
                    }
                    sb.append(chunk.getFullMethod());
                    texts.add(sb.toString());
                }

                List<float[]> vectors = embedBatch(texts);

                if (vectors.isEmpty()) {
                    List<Long> failedIds = batch.stream().map(ChunkEntity::getId).toList();
                    chunkRepo.updateEmbeddingStatus(failedIds, "FAILED");
                    failed += batch.size();
                    logCallback.accept("⚠️ 批次 embedding 失败 (batch start=" + i + ")");
                    continue;
                }

                List<VectorStoreService.VectorPoint> points = new ArrayList<>(vectors.size());
                List<Long> doneIds = new ArrayList<>(vectors.size());
                for (int j = 0; j < vectors.size() && j < batch.size(); j++) {
                    ChunkEntity chunk = batch.get(j);
                    String pointId = UUID.nameUUIDFromBytes(
                            (repoId + ":" + chunk.getFullMethod()).getBytes()).toString();
                    points.add(new VectorStoreService.VectorPoint(
                            pointId, vectors.get(j), repoId, chunk.getFullMethod(),
                            chunk.getAnnotations(),
                            chunk.getCallSummary() != null ? chunk.getCallSummary() : ""));
                    doneIds.add(chunk.getId());
                }

                try {
                    vectorStoreService.upsert(points);
                    chunkRepo.updateEmbeddingStatus(doneIds, "DONE");
                    done += points.size();
                } catch (Exception e) {
                    chunkRepo.updateEmbeddingStatus(doneIds, "FAILED");
                    failed += doneIds.size();
                    logCallback.accept("⚠️ Qdrant upsert 失败: " + e.getMessage());
                }

                int batchNum = i / batchSize + 1;
                // 每批都打进度日志，防止 SSE 连接超时断开
                {
                    int pct = total > 0 ? (done + failed) * 100 / total : 100;
                    logCallback.accept("⏳ 进度: " + (done + failed) + "/" + total + " (" + pct + "%) done=" + done + " failed=" + failed);
                    logger.info("[Embedding] 进度 repoId={}: {}/{}", repoId, done + failed, total);
                }
            }

            logCallback.accept("📊 向量建设完成: done=" + done + ", failed=" + failed + ", 总计=" + (alreadyDone + done));
            logger.info("[Embedding] 继续完成 repoId={}: done={}, failed={}", repoId, done, failed);

        } finally {
            embeddingRunning.set(false);
            currentEmbeddingRepoId = null;
        }
    }

    @Override
    public EmbeddingProgress getProgress(Long repoId) {
        long total = chunkRepo.countByRepoId(repoId);
        long done = chunkRepo.countByRepoIdAndEmbeddingStatus(repoId, "DONE");
        long failedCount = chunkRepo.countByRepoIdAndEmbeddingStatus(repoId, "FAILED");
        long pending = total - done - failedCount;
        String status = pending > 0 ? "IN_PROGRESS" : (failedCount > 0 ? "PARTIAL" : "DONE");
        return new EmbeddingProgress(total, done, failedCount, pending, status);
    }

    /**
     * 优先从 system_config 读取 API Key（运行时可配），其次用 application.yml 的值。
     */
    private String resolveApiKey() {
        return systemConfigRepo.findByConfigKey("embedding.api.key")
                .map(c -> c.getConfigValue())
                .filter(k -> k != null && !k.isBlank())
                .orElse(apiKey);
    }

    /**
     * 优先从 system_config 读取 API URL（运行时可配），其次用 application.yml 的值。
     * 自动补全协议头、去掉末尾斜杠，避免用户漏填 http(s):// 导致 URI 解析失败。
     */
    private String resolveApiUrl() {
        String url = systemConfigRepo.findByConfigKey("embedding.api.url")
                .map(c -> c.getConfigValue())
                .filter(u -> u != null && !u.isBlank())
                .orElse(apiUrl);
        return normalizeUrl(url);
    }

    /** 规整 URL：null/空返回空串；缺协议头补 https://；去掉末尾斜杠。 */
    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    @Override
    public boolean isConfigured() {
        String key = resolveApiKey();
        String url = resolveApiUrl();
        return key != null && !key.isBlank() && url != null && !url.isBlank();
    }
}
