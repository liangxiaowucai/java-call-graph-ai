package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.JarInfoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JarInfoRepo extends JpaRepository<JarInfoEntity, Long> {
    List<JarInfoEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
