package com.adrninistrator.javacg2.platform.service;

import java.util.List;

/**
 * 向量存储服务接口：负责向 Qdrant 写入和检索方法向量。
 */
public interface VectorStoreService {

    /**
     * 批量写入向量点。
     *
     * @param points 向量点列表
     */
    void upsert(List<VectorPoint> points);

    /**
     * 语义检索：按 query 向量在指定仓库集合中搜索最相似的方法。
     *
     * @param queryVector 查询向量
     * @param repoIds     限定搜索的仓库 ID 列表
     * @param topK        返回数量
     * @return 检索结果列表，按相似度降序
     */
    List<SearchResult> search(float[] queryVector, List<Long> repoIds, int topK);

    /**
     * 删除指定仓库的所有向量点（重新分析前调用）。
     *
     * @param repoId 仓库 ID
     */
    void deleteByRepoId(Long repoId);

    /**
     * 检查向量存储是否可用（Qdrant 健康检查）。
     */
    boolean isAvailable();

    /**
     * 确保 collection 存在，不存在则创建。
     *
     * @param dimensions 向量维度
     */
    void ensureCollection(int dimensions);

    record VectorPoint(String id, float[] vector, Long repoId, String fullMethod,
                       String endpointType, String summary) {}

    record SearchResult(String fullMethod, Long repoId, float score, String endpointType, String summary) {}
}
