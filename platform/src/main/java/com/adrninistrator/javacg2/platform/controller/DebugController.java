package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.DebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.EnhancedDebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.RequestChainDTO;
import com.adrninistrator.javacg2.platform.service.RequestChainAnalyzer;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.util.CurlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调试分析Controller
 */
@RestController
@RequestMapping("/api/debug")
@CrossOrigin(origins = "*", allowCredentials = "false")  // 允许Chrome插件跨域访问
public class DebugController {

    private static final Logger logger = LoggerFactory.getLogger(DebugController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RequestChainAnalyzer analyzer;
    private final CallGraphEngine callGraphEngine;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public DebugController(RequestChainAnalyzer analyzer, CallGraphEngine callGraphEngine) {
        this.analyzer = analyzer;
        this.callGraphEngine = callGraphEngine;
    }

    /**
     * 流式分析请求链（SSE）：实时推送分析进度、结果与 AI 输出。
     * 事件类型：progress(阶段进度) / result(分析结果JSON) / ai(AI流式token) / ai-done / done / error
     */
    @PostMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody String rawInput) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        sseExecutor.submit(() -> {
            try {
                RequestChainDTO chain = parseInput(rawInput);
                analyzer.analyzeStreaming(chain, new RequestChainAnalyzer.StreamListener() {
                    @Override
                    public void progress(String stage, String detail) {
                        send(emitter, "progress", java.util.Map.of("stage", stage, "detail", detail));
                    }
                    @Override
                    public void result(EnhancedDebugAnalysisResult result) {
                        send(emitter, "result", result);
                    }
                    @Override
                    public void aiToken(String token) {
                        send(emitter, "ai", java.util.Map.of("token", token));
                    }
                    @Override
                    public void aiDone(String fullText) {
                        send(emitter, "ai-done", java.util.Map.of("text", fullText));
                    }
                });
                send(emitter, "done", java.util.Map.of("ok", true));
                emitter.complete();
            } catch (Exception e) {
                logger.error("[流式分析] 失败", e);
                send(emitter, "error", java.util.Map.of("message", e.getMessage() != null ? e.getMessage() : "分析失败"));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = data instanceof String ? (String) data : objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            logger.debug("[SSE] 发送失败 event={}", event);
        }
    }
    
    /**
     * 按需展开调用树节点
     */
    @GetMapping("/expand-call-tree")
    public ResponseEntity<CallGraphEngine.CallTreeDTO> expandCallTreeNode(
            @RequestParam Long repoId,
            @RequestParam String fullMethod,
            @RequestParam(defaultValue = "5") int depth) {
        
        logger.debug("[按需展开] repoId={}, method={}, depth={}", repoId, fullMethod, depth);
        
        try {
            CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, fullMethod, depth);
            return ResponseEntity.ok(tree);
        } catch (Exception e) {
            logger.error("[按需展开] 失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 分析请求链 - 支持JSON和cURL两种格式
     */
    @PostMapping("/analyze-request-chain")
    public ResponseEntity<DebugAnalysisResult> analyzeRequestChain(@RequestBody String rawInput) {
        logger.info("[请求链分析] 收到原始输入, 长度={}", rawInput != null ? rawInput.length() : 0);
        
        try {
            // 解析输入
            RequestChainDTO chain = parseInput(rawInput);
            
            logger.info("[请求链分析] 解析后数据: sessionId={}, 用户操作={}, 请求数={}", 
                        chain.getSessionId(), chain.getUserAction(), 
                        chain.getRequestChain() != null ? chain.getRequestChain().size() : 0);
            
            // 执行分析
            DebugAnalysisResult result = analyzer.analyze(chain);
            
            logger.info("[请求链分析] 分析完成: sessionId={}, 失败请求数={}", 
                       chain.getSessionId(),
                       result.getApiCalls() != null ? 
                           result.getApiCalls().stream().filter(c -> c.getStatus() >= 400).count() : 0);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("[请求链分析] 分析失败", e);
            
            DebugAnalysisResult errorResult = new DebugAnalysisResult();
            errorResult.setSummary("分析失败: " + e.getMessage());
            errorResult.setReport("错误详情:\n" + e.toString());
            
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 解析输入 - 自动识别JSON或cURL格式
     */
    private RequestChainDTO parseInput(String rawInput) throws Exception {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            throw new IllegalArgumentException("输入为空");
        }
        
        String trimmed = rawInput.trim();
        
        // 检测cURL命令
        if (CurlParser.isCurlCommand(trimmed)) {
            logger.info("[请求链分析] 检测到cURL命令格式");
            return CurlParser.parseCurl(trimmed);
        }
        
        // 检测JSON格式
        if (CurlParser.isJson(trimmed)) {
            logger.info("[请求链分析] 检测到JSON格式");
            
            // 尝试解析为完整的 RequestChainDTO
            try {
                RequestChainDTO dto = objectMapper.readValue(trimmed, RequestChainDTO.class);
                
                // 如果 requestChain 为空，检查是否是单个请求对象
                if (dto.getRequestChain() == null || dto.getRequestChain().isEmpty()) {
                    logger.info("[请求链分析] requestChain为空，尝试解析为单个请求对象");
                    
                    // 尝试解析为单个 RequestInfo
                    RequestChainDTO.RequestInfo singleRequest = objectMapper.readValue(trimmed, RequestChainDTO.RequestInfo.class);
                    
                    // 包装成 RequestChainDTO
                    RequestChainDTO wrappedDto = new RequestChainDTO();
                    wrappedDto.setSessionId(singleRequest.getTimestamp()); // 使用时间戳作为 sessionId
                    wrappedDto.setTimestamp(singleRequest.getTimestamp());
                    wrappedDto.setUrl(singleRequest.getFullUrl() != null ? singleRequest.getFullUrl() : singleRequest.getUrl());
                    wrappedDto.setUserAction("单个请求分析");
                    wrappedDto.setRequestChain(List.of(singleRequest));
                    wrappedDto.setDataFlow(List.of());
                    
                    logger.info("[请求链分析] 成功将单个请求包装为请求链");
                    return wrappedDto;
                }
                
                return dto;
            } catch (Exception e) {
                logger.error("[请求链分析] JSON解析失败", e);
                throw new IllegalArgumentException("JSON格式错误: " + e.getMessage());
            }
        }
        
        // 尝试作为JSON解析（兜底）
        try {
            return objectMapper.readValue(trimmed, RequestChainDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法识别的输入格式，请提供有效的JSON或cURL命令");
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
    
    /**
     * Task #7: 对比多个请求链
     */
    @PostMapping("/compare-request-chains")
    public ResponseEntity<RequestChainAnalyzer.ComparisonResult> compareRequestChains(
            @RequestBody List<String> rawInputs) {
        logger.info("[请求链对比] 收到 {} 个请求链", rawInputs != null ? rawInputs.size() : 0);
        
        try {
            if (rawInputs == null || rawInputs.size() < 2) {
                throw new IllegalArgumentException("至少需要2个请求链才能进行对比");
            }
            
            // 解析所有输入
            List<RequestChainDTO> chains = new java.util.ArrayList<>();
            for (int i = 0; i < rawInputs.size(); i++) {
                try {
                    RequestChainDTO chain = parseInput(rawInputs.get(i));
                    chains.add(chain);
                } catch (Exception e) {
                    logger.error("[请求链对比] 解析第{}个请求链失败", i + 1, e);
                    throw new IllegalArgumentException(String.format("第%d个请求链解析失败: %s", i + 1, e.getMessage()));
                }
            }
            
            // 执行对比分析
            RequestChainAnalyzer.ComparisonResult result = analyzer.compareChains(chains);
            
            logger.info("[请求链对比] 对比完成: {}", result.summary());
            
            return ResponseEntity.ok(result);
            
        } catch (IllegalArgumentException e) {
            logger.warn("[请求链对比] 参数错误: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("[请求链对比] 对比失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 增强版请求链分析 - 整合时序图 + 调用树（懒加载）
     * 专为 Chrome 插件优化：初始只加载浅层调用树（3层），前端可按需展开
     */
    @PostMapping("/analyze-with-callgraph")
    public ResponseEntity<EnhancedDebugAnalysisResult> analyzeWithCallGraph(@RequestBody String rawInput) {
        logger.info("[增强请求链分析] 收到原始输入, 长度={}", rawInput != null ? rawInput.length() : 0);
        
        try {
            // 解析输入
            RequestChainDTO chain = parseInput(rawInput);
            
            logger.info("[增强请求链分析] 解析后数据: sessionId={}, 用户操作={}, 请求数={}", 
                        chain.getSessionId(), chain.getUserAction(), 
                        chain.getRequestChain() != null ? chain.getRequestChain().size() : 0);
            
            // 调用增强分析服务
            EnhancedDebugAnalysisResult result = analyzer.analyzeWithCallGraph(chain);
            
            logger.info("[增强请求链分析] 分析完成: 请求数={}, 涉及仓库={}", 
                       result.getRequestSequence() != null ? result.getRequestSequence().size() : 0,
                       result.getRecommendedRepos() != null ? result.getRecommendedRepos().size() : 0);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("[增强请求链分析] 分析失败", e);
            
            EnhancedDebugAnalysisResult errorResult = new EnhancedDebugAnalysisResult();
            errorResult.setSummary("分析失败: " + e.getMessage());
            errorResult.setReport("错误详情:\n" + e.toString());
            
            return ResponseEntity.status(500).body(errorResult);
        }
    }
}
