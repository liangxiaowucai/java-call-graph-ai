package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.EnumConstantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EnumConstantRepo extends JpaRepository<EnumConstantEntity, Long> {
    List<EnumConstantEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
