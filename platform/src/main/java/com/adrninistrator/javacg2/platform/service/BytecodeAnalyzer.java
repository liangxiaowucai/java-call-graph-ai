package com.adrninistrator.javacg2.platform.service;

public interface BytecodeAnalyzer {

    /** 执行全量分析 */
    AnalysisResult analyzeFullProject(Long repoId, boolean forceRebuild);

    /** 补跑搜索索引和仓库画像（不重新编译/分析，只读已有产物和源码） */
    void rebuildIndex(Long repoId);

    /** 获取分析输出目录路径 */
    String getOutputDir(Long repoId);

    record AnalysisResult(boolean success, String message, int methodCount, int callCount) {}
}
