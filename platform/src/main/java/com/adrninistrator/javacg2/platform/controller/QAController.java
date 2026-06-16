package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.service.impl.ClaudeApiClient;
import com.adrninistrator.javacg2.platform.service.impl.QAEngineImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/qa")
public class QAController {

    private static final Logger logger = LoggerFactory.getLogger(QAController.class);

    private final QAEngineImpl qaEngine;
    private final ClaudeApiClient claudeClient;

    // SSE 响应用独立线程池，不阻塞 Tomcat 请求线程
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-qa-worker");
        t.setDaemon(true);
        return t;
    });

    public QAController(QAEngineImpl qaEngine, ClaudeApiClient claudeClient) {
        this.qaEngine = qaEngine;
        this.claudeClient = claudeClient;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of("configured", claudeClient.isConfigured()));
    }

    @GetMapping("/search")
    public ApiResponse<List<QAEngineImpl.EndpointSearchResult>> search(
            @RequestParam Long repoId,
            @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(qaEngine.searchEndpoints(repoId, keyword));
    }

    @GetMapping("/preset")
    public ApiResponse<String> preset(
            @RequestParam Long repoId,
            @RequestParam String method,
            @RequestParam String type) {
        return ApiResponse.ok(qaEngine.presetAnswer(repoId, method, type));
    }

    /**
     * 普通问答（已选接口）- SSE 流式返回
     * 前端通过 EventSource 消费，事件类型：thinking / answer / done / error
     */
    @PostMapping(value = "/ask", produces = "text/event-stream")
    public SseEmitter ask(@RequestBody Map<String, Object> body) {
        Long repoId = Long.valueOf(body.get("repoId").toString());
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) body.get("methods");
        String question = (String) body.get("question");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = body.containsKey("history")
                ? (List<Map<String, String>>) body.get("history") : List.of();

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.submit(() -> {
            try {
                logger.info("[AI问答-SSE] repoId={}, 接口数={}, 问题: {}", repoId, methods.size(), question);

                // 先找到对应的 ScoredEndpoint 列表
                List<QAEngineImpl.ScoredEndpoint> scored = qaEngine.resolveMethodsToScored(repoId, methods);
                List<QAEngineImpl.MatchedEndpoint> matched = scored.stream()
                        .map(qaEngine::toMatchedEndpointPublic)
                        .collect(Collectors.toList());

                // 推送每轮工具调用步骤
                QAEngineImpl.SmartQAResponse result = qaEngine.generateAnswerWithLoop(
                        scored, question, List.of(), matched,
                        step -> sendSseEvent(emitter, "thinking", Map.of(
                                "content", step.label(),
                                "round", step.round(),
                                "toolName", step.toolName()
                        ))
                );

                sendSseEvent(emitter, "answer", Map.of("content", result.answer()));
                sendSseEvent(emitter, "done", Map.of(
                        "references", result.references(),
                        "matchedEndpoints", result.matchedEndpoints()
                ));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[AI问答-SSE] 失败", e);
                sendSseEvent(emitter, "error", Map.of("content", "❌ " + e.getMessage()));
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 智能问答（自动搜索接口）- SSE 流式返回
     * 非 SSE 阶段（意图确认、接口候选）直接返回 JSON，用旧接口兼容；
     * 确认后的实际问答阶段走 SSE。
     */
    @PostMapping(value = "/smart-ask", produces = "text/event-stream")
    public SseEmitter smartAsk(@RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        @SuppressWarnings("unchecked")
        List<Number> repoIdNums = body.containsKey("repoIds") ? (List<Number>) body.get("repoIds") : List.of();
        List<Long> repoIds = repoIdNums.stream().map(Number::longValue).collect(Collectors.toList());
        @SuppressWarnings("unchecked")
        List<String> confirmedMethods = body.containsKey("confirmedMethods")
                ? (List<String>) body.get("confirmedMethods") : List.of();

        QAEngineImpl.IntentConfirmation confirmedIntent = null;
        if (body.containsKey("confirmedIntent") && body.get("confirmedIntent") != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> ci = (Map<String, String>) body.get("confirmedIntent");
            confirmedIntent = new QAEngineImpl.IntentConfirmation(
                    ci.get("intentType"), ci.get("intentLabel"), ci.get("clarification"));
        }

        final QAEngineImpl.IntentConfirmation finalConfirmedIntent = confirmedIntent;
        SseEmitter emitter = new SseEmitter(300_000L);

        sseExecutor.submit(() -> {
            try {
                logger.info("[智能问答-SSE] 问题: {}, 仓库: {}", question, repoIds);

                // 先走召回 + rerank（非 SSE 阶段）
                QAEngineImpl.SmartQAIntermediate intermediate =
                        qaEngine.smartAskIntermediate(repoIds, question, confirmedMethods, finalConfirmedIntent);

                // 需要用户确认（意图/接口）→ 以 JSON 形式通过 SSE 返回，前端处理
                if (intermediate.needsConfirmation() || intermediate.needsIntentConfirmation()) {
                    sendSseEvent(emitter, "confirmation", Map.of(
                            "answer", intermediate.answer(),
                            "needsConfirmation", intermediate.needsConfirmation(),
                            "needsIntentConfirmation", intermediate.needsIntentConfirmation(),
                            "keywords", intermediate.keywords(),
                            "matchedEndpoints", intermediate.matchedEndpoints(),
                            "intentResult", intermediate.intentResult() != null ? intermediate.intentResult() : Map.of()
                    ));
                    emitter.complete();
                    return;
                }

                // 正常路径：推送思考步骤 + 最终回答
                List<QAEngineImpl.ScoredEndpoint> topResults = intermediate.topResults();
                List<QAEngineImpl.MatchedEndpoint> matchedEndpoints = intermediate.matchedEndpoints();

                // 推送"已匹配接口"信息
                sendSseEvent(emitter, "matched", Map.of(
                        "keywords", intermediate.keywords(),
                        "matchedEndpoints", matchedEndpoints
                ));

                // loop 问答，每轮工具调用实时推送
                QAEngineImpl.SmartQAResponse result = qaEngine.generateAnswerWithLoop(
                        topResults, question, intermediate.keywords(), matchedEndpoints,
                        step -> sendSseEvent(emitter, "thinking", Map.of(
                                "content", step.label(),
                                "round", step.round(),
                                "toolName", step.toolName()
                        ))
                );

                sendSseEvent(emitter, "answer", Map.of("content", result.answer()));
                sendSseEvent(emitter, "done", Map.of(
                        "references", result.references(),
                        "cached", false
                ));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[智能问答-SSE] 失败", e);
                sendSseEvent(emitter, "error", Map.of("content", "❌ " + e.getMessage()));
                emitter.complete();
            }
        });

        return emitter;
    }

    // ── 兼容旧接口（非 SSE，供测试或降级使用）───────────────────────────────────

    @PostMapping("/ask-sync")
    public ApiResponse<QAEngineImpl.QAResponse> askSync(@RequestBody Map<String, Object> body) {
        Long repoId = Long.valueOf(body.get("repoId").toString());
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) body.get("methods");
        String question = (String) body.get("question");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = body.containsKey("history")
                ? (List<Map<String, String>>) body.get("history") : List.of();
        return ApiResponse.ok(qaEngine.ask(repoId, methods, question, history));
    }

    @PostMapping("/smart-ask-sync")
    public ApiResponse<QAEngineImpl.SmartQAResponse> smartAskSync(@RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        @SuppressWarnings("unchecked")
        List<Number> repoIdNums = body.containsKey("repoIds") ? (List<Number>) body.get("repoIds") : List.of();
        List<Long> repoIds = repoIdNums.stream().map(Number::longValue).collect(Collectors.toList());
        @SuppressWarnings("unchecked")
        List<String> confirmedMethods = body.containsKey("confirmedMethods")
                ? (List<String>) body.get("confirmedMethods") : List.of();
        QAEngineImpl.IntentConfirmation confirmedIntent = null;
        if (body.containsKey("confirmedIntent") && body.get("confirmedIntent") != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> ci = (Map<String, String>) body.get("confirmedIntent");
            confirmedIntent = new QAEngineImpl.IntentConfirmation(
                    ci.get("intentType"), ci.get("intentLabel"), ci.get("clarification"));
        }
        return ApiResponse.ok(qaEngine.smartAsk(repoIds, question, confirmedMethods, confirmedIntent));
    }

    // ── SSE 工具方法 ──────────────────────────────────────────────────────────

    private void sendSseEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventType).data(json));
        } catch (IOException e) {
            logger.debug("[SSE] 发送事件失败（客户端可能已断开）: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("[SSE] 事件序列化失败: {}", e.getMessage());
        }
    }
}
