package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.GraphLayoutEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GraphLayoutRepo extends JpaRepository<GraphLayoutEntity, Long> {
    List<GraphLayoutEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
    long countByRepoId(Long repoId);
}
