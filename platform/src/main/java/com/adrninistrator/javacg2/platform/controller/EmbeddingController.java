package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.service.EmbeddingService;
import com.adrninistrator.javacg2.platform.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * Embedding 管理接口：手动触发（SSE 实时日志）/查询向量索引状态。
 */
@RestController
@RequestMapping("/api/embedding")
public class EmbeddingController {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingController.class);

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final ChunkRepo chunkRepo;

    public EmbeddingController(EmbeddingService embeddingService,
                                VectorStoreService vectorStoreService,
                                ChunkRepo chunkRepo) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.chunkRepo = chunkRepo;
    }

    /**
     * 手动触发指定仓库的向量重建（SSE 流式推送日志）。
     * POST /api/embedding/rebuild?repoId=1
     * 返回 text/event-stream，前端通过 EventSource/fetch 消费。
     */
    @PostMapping(value = "/rebuild", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rebuild(@RequestParam Long repoId) {
        // 超时设置为 30 分钟（大仓库向量建设可能耗时很长）
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        long total = chunkRepo.countByRepoId(repoId);
        logger.info("[EmbeddingController] 手动触发向量重建(SSE) repoId={}, total={}", repoId, total);

        // 在新线程中同步执行，通过 callback 推送 SSE 事件
        new Thread(() -> {
            try {
                embeddingService.embedChunksWithCallback(repoId, (logLine) -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("log")
                                .data(logLine));
                    } catch (Exception e) {
                        // 客户端断开连接，忽略
                        logger.debug("[SSE] 发送失败（客户端可能已断开）: {}", e.getMessage());
                    }
                });
                // 完成事件
                emitter.send(SseEmitter.event().name("done").data("完成"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("❌ " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
                logger.error("[EmbeddingController] 向量重建异常 repoId={}", repoId, e);
            }
        }).start();

        return emitter;
    }

    /**
     * 继续未完成的向量建设（SSE 流式推送日志）。
     * POST /api/embedding/continue?repoId=1
     */
    @PostMapping(value = "/continue", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueEmbedding(@RequestParam Long repoId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        logger.info("[EmbeddingController] 继续向量建设(SSE) repoId={}", repoId);

        new Thread(() -> {
            try {
                embeddingService.continueEmbedding(repoId, (logLine) -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("log")
                                .data(logLine));
                    } catch (Exception e) {
                        logger.debug("[SSE] 发送失败（客户端可能已断开）: {}", e.getMessage());
                    }
                });
                emitter.send(SseEmitter.event().name("done").data("完成"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("❌ " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
                logger.error("[EmbeddingController] 继续向量建设异常 repoId={}", repoId, e);
            }
        }).start();

        return emitter;
    }

    /**
     * 查询指定仓库的向量索引进度。
     * GET /api/embedding/status?repoId=2
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(@RequestParam Long repoId) {
        EmbeddingService.EmbeddingProgress progress = embeddingService.getProgress(repoId);
        Map<String, Object> data = new HashMap<>();
        data.put("repoId", repoId);
        data.put("total", progress.total());
        data.put("done", progress.done());
        data.put("failed", progress.failed());
        data.put("pending", progress.pending());
        data.put("status", progress.status());
        data.put("qdrantAvailable", vectorStoreService.isAvailable());
        return ApiResponse.ok(data);
    }
}
