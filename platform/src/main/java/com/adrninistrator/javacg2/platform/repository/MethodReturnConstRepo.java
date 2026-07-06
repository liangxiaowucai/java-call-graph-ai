package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.MethodReturnConstEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MethodReturnConstRepo extends JpaRepository<MethodReturnConstEntity, Long> {
    List<MethodReturnConstEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
