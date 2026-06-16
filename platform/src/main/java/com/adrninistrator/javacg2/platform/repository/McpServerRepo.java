package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServerRepo extends JpaRepository<McpServerEntity, Long> {
    List<McpServerEntity> findAllByOrderByCreatedAtAsc();
    List<McpServerEntity> findByEnabledTrue();
}
