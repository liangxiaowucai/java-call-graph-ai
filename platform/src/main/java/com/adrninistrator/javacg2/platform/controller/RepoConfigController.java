package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.entity.RepoConfigEntity;
import com.adrninistrator.javacg2.platform.repository.RepoConfigRepo;
import com.adrninistrator.javacg2.platform.service.impl.ConfigValueExtractor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}/config")
public class RepoConfigController {

    private final RepoConfigRepo repoConfigRepo;
    private final ConfigValueExtractor configExtractor;

    public RepoConfigController(RepoConfigRepo repoConfigRepo, ConfigValueExtractor configExtractor) {
        this.repoConfigRepo = repoConfigRepo;
        this.configExtractor = configExtractor;
    }

    @GetMapping
    public ApiResponse<List<RepoConfigEntity>> list(@PathVariable Long repoId,
                                                     @RequestParam(required = false) String search) {
        List<RepoConfigEntity> configs = repoConfigRepo.findByRepoId(repoId);
        if (search != null && !search.isBlank()) {
            String lower = search.toLowerCase();
            configs = configs.stream()
                    .filter(c -> c.getConfigKey().toLowerCase().contains(lower)
                            || (c.getConfigValue() != null && c.getConfigValue().toLowerCase().contains(lower)))
                    .toList();
        }
        return ApiResponse.ok(configs);
    }

    @PutMapping
    public ApiResponse<String> update(@PathVariable Long repoId, @RequestBody Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key == null) return ApiResponse.error("VALIDATION_ERROR", "key 不能为空", "");

        RepoConfigEntity entity = repoConfigRepo.findByRepoIdAndConfigKey(repoId, key).orElseGet(() -> {
            RepoConfigEntity e = new RepoConfigEntity();
            e.setRepoId(repoId);
            e.setConfigKey(key);
            return e;
        });
        entity.setConfigValue(value);
        entity.setSource("USER");
        entity.setUpdatedAt(LocalDateTime.now());
        repoConfigRepo.save(entity);
        return ApiResponse.ok("已保存");
    }

    /**
     * 上传配置文件（yml/properties），支持全量覆盖或增量合并
     * mode=merge（默认）: 增量合并，只更新上传文件中有的 key
     * mode=replace: 全量覆盖，先清除所有 USER 来源的配置再导入
     */
    @PostMapping("/upload")
    public ApiResponse<Integer> uploadConfig(@PathVariable Long repoId,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue = "merge") String mode) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml") && !fileName.endsWith(".properties"))) {
            return ApiResponse.error("VALIDATION_ERROR", "请上传 .yml 或 .properties 文件", "");
        }

        try {
            // 保存到临时文件
            Path tempDir = Files.createTempDirectory("config-upload");
            Path tempFile = tempDir.resolve(fileName);
            file.transferTo(tempFile.toFile());

            // 解析配置文件
            Map<String, String> uploaded = fileName.endsWith(".properties")
                    ? configExtractor.parsePropertiesFile(tempFile)
                    : configExtractor.parseYamlFile(tempFile);

            // 清理临时文件
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);

            if (uploaded.isEmpty()) {
                return ApiResponse.error("EMPTY", "配置文件为空或解析失败", "");
            }

            // 全量模式：先清除所有 USER 来源的配置
            if ("replace".equals(mode)) {
                List<RepoConfigEntity> existing = repoConfigRepo.findByRepoId(repoId);
                List<RepoConfigEntity> userConfigs = existing.stream()
                        .filter(c -> "USER".equals(c.getSource()))
                        .toList();
                repoConfigRepo.deleteAll(userConfigs);
            }

            // 导入
            List<RepoConfigEntity> existing = repoConfigRepo.findByRepoId(repoId);
            Map<String, RepoConfigEntity> existingMap = new java.util.HashMap<>();
            for (RepoConfigEntity e : existing) existingMap.put(e.getConfigKey(), e);

            List<RepoConfigEntity> toSave = new java.util.ArrayList<>();
            for (Map.Entry<String, String> entry : uploaded.entrySet()) {
                RepoConfigEntity entity = existingMap.get(entry.getKey());
                if (entity != null) {
                    entity.setConfigValue(entry.getValue());
                    entity.setSource("USER");
                    entity.setUpdatedAt(LocalDateTime.now());
                    toSave.add(entity);
                } else {
                    entity = new RepoConfigEntity();
                    entity.setRepoId(repoId);
                    entity.setConfigKey(entry.getKey());
                    entity.setConfigValue(entry.getValue());
                    entity.setSource("USER");
                    entity.setUpdatedAt(LocalDateTime.now());
                    toSave.add(entity);
                }
            }
            repoConfigRepo.saveAll(toSave);

            return ApiResponse.ok(toSave.size());
        } catch (IOException e) {
            return ApiResponse.error("IO_ERROR", "文件处理失败: " + e.getMessage(), "");
        }
    }
}
