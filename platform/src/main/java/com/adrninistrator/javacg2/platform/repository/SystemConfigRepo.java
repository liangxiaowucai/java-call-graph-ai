package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.SystemConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SystemConfigRepo extends JpaRepository<SystemConfigEntity, Long> {
    Optional<SystemConfigEntity> findByConfigKey(String configKey);
}
