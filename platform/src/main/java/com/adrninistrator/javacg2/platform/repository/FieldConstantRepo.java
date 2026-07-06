package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.FieldConstantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FieldConstantRepo extends JpaRepository<FieldConstantEntity, Long> {
    List<FieldConstantEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
