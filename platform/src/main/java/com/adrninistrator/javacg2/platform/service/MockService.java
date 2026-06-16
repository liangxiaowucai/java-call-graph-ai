package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.SystemConfigEntity;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Mock 服务：为外部调用（HTTP/gRPC）配置 Mock 入参和返回值
 */
@Service
public class MockService {

    private final SystemConfigRepo configRepo;

    public MockService(SystemConfigRepo configRepo) {
        this.configRepo = configRepo;
    }

    /**
     * 获取某个方法的 Mock 配置
     * key 格式: mock.{repoId}.{fullMethod}
     */
    public MockConfig getMock(Long repoId, String fullMethod) {
        String key = "mock." + repoId + "." + fullMethod;
        Optional<SystemConfigEntity> config = configRepo.findByConfigKey(key);
        if (config.isEmpty()) return null;

        String value = config.get().getConfigValue();
        // 格式: requestBody|||responseBody
        String[] parts = value.split("\\|\\|\\|", 2);
        return new MockConfig(
                parts.length > 0 ? parts[0] : "",
                parts.length > 1 ? parts[1] : ""
        );
    }

    /**
     * 保存 Mock 配置
     */
    public void saveMock(Long repoId, String fullMethod, String mockRequest, String mockResponse) {
        String key = "mock." + repoId + "." + fullMethod;
        String value = (mockRequest != null ? mockRequest : "") + "|||" + (mockResponse != null ? mockResponse : "");

        SystemConfigEntity entity = configRepo.findByConfigKey(key).orElseGet(() -> {
            SystemConfigEntity e = new SystemConfigEntity();
            e.setConfigKey(key);
            return e;
        });
        entity.setConfigValue(value);
        entity.setUpdatedAt(LocalDateTime.now());
        configRepo.save(entity);
    }

    /**
     * 删除 Mock 配置
     */
    public void deleteMock(Long repoId, String fullMethod) {
        String key = "mock." + repoId + "." + fullMethod;
        configRepo.findByConfigKey(key).ifPresent(configRepo::delete);
    }

    public record MockConfig(String mockRequest, String mockResponse) {}
}
