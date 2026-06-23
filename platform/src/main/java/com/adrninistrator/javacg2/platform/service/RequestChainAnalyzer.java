package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.dto.DebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.EnhancedDebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.RequestChainDTO;

import java.util.List;

/**
 * 请求链分析服务
 */
public interface RequestChainAnalyzer {
    
    /**
     * 分析请求链
     * @param chain 请求链数据
     * @return 分析结果
     */
    DebugAnalysisResult analyze(RequestChainDTO chain);
    
    /**
     * 增强版请求链分析 - 整合时序图 + 调用树（懒加载）
     * @param chain 请求链数据
     * @return 增强分析结果
     */
    EnhancedDebugAnalysisResult analyzeWithCallGraph(RequestChainDTO chain);

    /**
     * 流式分析：实时回调分析进度、结果与 AI token，用于 SSE 推送。
     */
    void analyzeStreaming(RequestChainDTO chain, StreamListener listener);

    /**
     * 流式分析监听器
     */
    interface StreamListener {
        /** 阶段进度：stage=阶段标识, detail=人类可读的中间信息 */
        void progress(String stage, String detail);
        /** 分析结果（AI 之前）就绪 */
        void result(EnhancedDebugAnalysisResult result);
        /** AI 流式 token */
        void aiToken(String token);
        /** AI 输出结束（完整文本） */
        void aiDone(String fullText);
    }
    
    /**
     * Task #7: 对比多次请求链，找出性能差异
     * @param chains 多个请求链数据（2个或更多）
     * @return 对比分析结果
     */
    ComparisonResult compareChains(List<RequestChainDTO> chains);
    
    /**
     * 请求链对比结果
     */
    record ComparisonResult(
        String summary,
        List<ChainSummary> chainSummaries,
        List<PerformanceDiff> performanceDiffs,
        List<String> insights,
        String report
    ) {}
    
    /**
     * 单个请求链的摘要
     */
    record ChainSummary(
        int index,
        String sessionId,
        long totalDuration,
        int requestCount,
        int failedCount,
        int dbCallCount,
        int httpCallCount,
        int score
    ) {}
    
    /**
     * 性能差异项
     */
    record PerformanceDiff(
        String metric,
        List<Long> values,
        long minValue,
        long maxValue,
        double avgValue,
        double variance,
        String analysis
    ) {}
}

