package com.adrninistrator.javacg2.platform.service;

import java.util.List;

/**
 * Embedding 服务接口：将文本转换为向量，支持单条和批量。
 */
public interface EmbeddingService {

    /**
     * Embedding 是否已配置可用（API Key 与 API 地址均已填写且地址合法）。
     * 向量检索为选配功能：未配置时分析仓库不应触发 embedding，也不报错。
     *
     * @return true 表示已配置，可生成向量
     */
    boolean isConfigured();

    /**
     * 将单段文本转为向量。
     *
     * @param text 待 embed 的文本
     * @return float 数组，长度由模型决定（text-embedding-3-large = 3072）
     */
    float[] embed(String text);

    /**
     * 批量将文本转为向量，与输入顺序一一对应。
     *
     * @param texts 待 embed 的文本列表
     * @return 每条文本对应的向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 异步为指定仓库的所有 chunk 生成向量并写入向量存储。
     * 分析完成后触发，不阻塞主流程。
     *
     * @param repoId 仓库 ID
     */
    void embedChunksBatchAsync(Long repoId);

    /**
     * 同步为指定仓库的所有 chunk 生成向量并写入向量存储。
     * 通过 logCallback 实时推送日志给调用方（用于 SSE 流式输出）。
     *
     * @param repoId      仓库 ID
     * @param logCallback 每条日志回调
     */
    void embedChunksWithCallback(Long repoId, java.util.function.Consumer<String> logCallback);

    /**
     * 继续未完成的向量建设（只处理非 DONE 状态的 chunks，不删旧向量）。
     *
     * @param repoId      仓库 ID
     * @param logCallback 每条日志回调
     */
    void continueEmbedding(Long repoId, java.util.function.Consumer<String> logCallback);

    /**
     * 获取指定仓库的 embedding 进度。
     *
     * @param repoId 仓库 ID
     * @return 进度信息
     */
    EmbeddingProgress getProgress(Long repoId);

    record EmbeddingProgress(long total, long done, long failed, long pending, String status) {}
}
