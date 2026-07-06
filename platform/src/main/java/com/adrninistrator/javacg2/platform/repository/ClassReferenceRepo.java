package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.ClassReferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClassReferenceRepo extends JpaRepository<ClassReferenceEntity, Long> {
    List<ClassReferenceEntity> findByRepoId(Long repoId);
    void deleteByRepoId(Long repoId);
}
