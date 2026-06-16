package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BoundaryRepo extends JpaRepository<BoundaryEntity, Long> {
    List<BoundaryEntity> findByRepoId(Long repoId);
    List<BoundaryEntity> findByRepoIdAndFullMethod(Long repoId, String fullMethod);
    List<BoundaryEntity> findByRepoIdAndBoundaryType(Long repoId, String boundaryType);
    void deleteByRepoId(Long repoId);
}
