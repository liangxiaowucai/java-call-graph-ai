package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.StaticFieldUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StaticFieldUsageRepo extends JpaRepository<StaticFieldUsageEntity, Long> {
    List<StaticFieldUsageEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
