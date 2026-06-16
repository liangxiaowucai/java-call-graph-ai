package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.RepoConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RepoConfigRepo extends JpaRepository<RepoConfigEntity, Long> {
    List<RepoConfigEntity> findByRepoId(Long repoId);
    Optional<RepoConfigEntity> findByRepoIdAndConfigKey(Long repoId, String configKey);
    void deleteByRepoId(Long repoId);
}
