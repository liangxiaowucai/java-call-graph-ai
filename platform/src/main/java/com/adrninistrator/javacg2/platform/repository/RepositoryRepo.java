package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RepositoryRepo extends JpaRepository<RepositoryEntity, Long> {
    Optional<RepositoryEntity> findByGitUrl(String gitUrl);
    boolean existsByLocalPath(String localPath);
}
