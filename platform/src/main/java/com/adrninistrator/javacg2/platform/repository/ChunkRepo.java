package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChunkRepo extends JpaRepository<ChunkEntity, Long> {

    List<ChunkEntity> findByRepoId(Long repoId);

    Optional<ChunkEntity> findByRepoIdAndFullMethod(Long repoId, String fullMethod);

    /** 全局查找定义了该方法的所有 chunk（跨仓库），用于跨库追踪时定位方法归属 */
    List<ChunkEntity> findByFullMethod(String fullMethod);

    List<ChunkEntity> findByRepoIdAndClassName(Long repoId, String className);

    List<ChunkEntity> findByRepoIdAndPackageName(Long repoId, String packageName);

    void deleteByRepoId(Long repoId);

    void deleteByRepoIdAndFullMethodIn(Long repoId, List<String> fullMethods);

    // ── Embedding 相关 ────────────────────────────────────────────────────────

    long countByRepoId(Long repoId);

    long countByRepoIdAndEmbeddingStatus(Long repoId, String embeddingStatus);

    @Transactional
    @Modifying
    @Query("UPDATE ChunkEntity c SET c.embeddingStatus = :status WHERE c.id IN :ids")
    void updateEmbeddingStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE ChunkEntity c SET c.embeddingStatus = NULL WHERE c.repoId = :repoId")
    void resetEmbeddingStatus(@Param("repoId") Long repoId);

    /** 批量清空 call_summary（rebuildIndex 专用，替代全量 SELECT + loop + saveAll） */
    @Transactional
    @Modifying
    @Query("UPDATE ChunkEntity c SET c.callSummary = NULL WHERE c.repoId = :repoId")
    void clearCallSummaryByRepoId(@Param("repoId") Long repoId);

    @Query("SELECT c FROM ChunkEntity c WHERE c.repoId = :repoId AND (c.embeddingStatus IS NULL OR c.embeddingStatus != 'DONE')")
    List<ChunkEntity> findPendingByRepoId(@Param("repoId") Long repoId);
}
