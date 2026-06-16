package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CallGraphRepo extends JpaRepository<CallGraphEntity, Long> {
    List<CallGraphEntity> findByRepoIdAndCallerMethod(Long repoId, String callerMethod);
    List<CallGraphEntity> findByRepoIdAndCalleeMethod(Long repoId, String calleeMethod);
    List<CallGraphEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);

    // ── 跨库查询（不带 repoId，按方法签名全局检索）────────────────────────────
    /** 全局查找调用了该方法的所有边（跨所有仓库），用于跨库影响分析 */
    List<CallGraphEntity> findByCalleeMethod(String calleeMethod);
    /** 全局查找该方法调用的所有边（跨所有仓库），用于跨库调用链展开 */
    List<CallGraphEntity> findByCallerMethod(String callerMethod);
}
