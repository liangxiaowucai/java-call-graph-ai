package com.adrninistrator.javacg2.platform.service;

import java.util.List;
import java.util.Map;

public interface LogAnalyzer {

    /**
     * 分析日志，匹配到调用链节点，标记每个节点的状态
     */
    LogAnalysisResult analyze(Long repoId, String entryMethod, String logText);

    record LogAnalysisResult(
        List<NodeStatus> nodeStatuses,
        String summary,
        String extractedUrl,
        String extractedException
    ) {}

    record NodeStatus(
        String fullMethod,
        String status,        // OK / ERROR / UNKNOWN
        String errorMessage,  // 具体报错内容（仅 ERROR 时有值）
        int logLineNumber,    // 日志中匹配到的行号（-1 表示未匹配）
        String logSnippet     // 匹配到的日志片段
    ) {}
}
