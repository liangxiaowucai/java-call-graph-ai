package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.RepoConfigEntity;
import com.adrninistrator.javacg2.platform.repository.RepoConfigRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ConfigValueExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigValueExtractor.class);

    private final RepoConfigRepo repoConfigRepo;

    public ConfigValueExtractor(RepoConfigRepo repoConfigRepo) {
        this.repoConfigRepo = repoConfigRepo;
    }

    /**
     * 扫描仓库配置文件，提取并保存到 repo_config 表
     */
    public Map<String, String> extractAndSave(Long repoId, String repoPath) {
        Map<String, String> allConfigs = extractAllConfigs(repoPath);

        // 批量保存，不逐条 save
        List<RepoConfigEntity> existing = repoConfigRepo.findByRepoId(repoId);
        Map<String, RepoConfigEntity> existingMap = new java.util.HashMap<>();
        for (RepoConfigEntity e : existing) existingMap.put(e.getConfigKey(), e);

        List<RepoConfigEntity> toSave = new ArrayList<>();
        for (Map.Entry<String, String> entry : allConfigs.entrySet()) {
            RepoConfigEntity entity = existingMap.get(entry.getKey());
            if (entity != null) {
                if (!"USER".equals(entity.getSource())) {
                    entity.setConfigValue(entry.getValue());
                    entity.setSource("FILE");
                    entity.setUpdatedAt(LocalDateTime.now());
                    toSave.add(entity);
                }
            } else {
                entity = new RepoConfigEntity();
                entity.setRepoId(repoId);
                entity.setConfigKey(entry.getKey());
                entity.setConfigValue(entry.getValue());
                entity.setSource("FILE");
                entity.setUpdatedAt(LocalDateTime.now());
                toSave.add(entity);
            }
        }
        if (!toSave.isEmpty()) {
            repoConfigRepo.saveAll(toSave);
        }
        logger.info("保存 {} 项配置到 repo_config", toSave.size());
        return allConfigs;
    }

    /**
     * 获取有效值：USER > FILE > defaultValue
     */
    public String getEffectiveValue(Long repoId, String key, String defaultValue) {
        Optional<RepoConfigEntity> config = repoConfigRepo.findByRepoIdAndConfigKey(repoId, key);
        if (config.isPresent() && config.get().getConfigValue() != null && !config.get().getConfigValue().isBlank()) {
            return config.get().getConfigValue();
        }
        // relaxed binding
        config = repoConfigRepo.findByRepoIdAndConfigKey(repoId, key.replace('-', '.'));
        if (config.isPresent() && config.get().getConfigValue() != null && !config.get().getConfigValue().isBlank()) {
            return config.get().getConfigValue();
        }
        config = repoConfigRepo.findByRepoIdAndConfigKey(repoId, key.replace('.', '-'));
        if (config.isPresent() && config.get().getConfigValue() != null && !config.get().getConfigValue().isBlank()) {
            return config.get().getConfigValue();
        }
        return defaultValue;
    }

    public Map<String, String> extractAllConfigs(String repoPath) {
        Map<String, String> allConfigs = new LinkedHashMap<>();
        try (var walk = Files.walk(Path.of(repoPath), 8)) {
            List<Path> configFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String pathStr = p.toString();
                        if (!pathStr.contains("src/main/resources") && !pathStr.contains("src\\main\\resources")) return false;
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .collect(Collectors.toList());

            for (Path configFile : configFiles) {
                try {
                    String name = configFile.getFileName().toString().toLowerCase();
                    Map<String, String> configs = name.endsWith(".properties") ? parseProperties(configFile) : parseYaml(configFile);
                    allConfigs.putAll(configs);
                    logger.debug("解析配置文件: {} ({} 项)", configFile, configs.size());
                } catch (Exception e) {
                    logger.warn("解析配置文件失败: {}", configFile, e);
                }
            }
        } catch (IOException e) {
            logger.error("扫描配置文件失败: {}", repoPath, e);
        }
        logger.info("共提取 {} 项配置", allConfigs.size());
        return allConfigs;
    }

    public Map<String, String> findRelatedConfigs(Map<String, String> allConfigs, String... keywords) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : allConfigs.entrySet()) {
            String key = entry.getKey().toLowerCase();
            for (String keyword : keywords) {
                if (key.contains(keyword.toLowerCase())) {
                    result.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        return result;
    }

    public Map<String, String> parsePropertiesFile(Path file) throws IOException {
        return parseProperties(file);
    }

    public Map<String, String> parseYamlFile(Path file) {
        return parseYaml(file);
    }

    private Map<String, String> parseProperties(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) { props.load(is); }
        for (String key : props.stringPropertyNames()) result.put(key, props.getProperty(key));
        return result;
    }

    private Map<String, String> parseYaml(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(file)) {
            for (Object doc : yaml.loadAll(is)) {
                if (doc instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) doc;
                    flattenMap("", map, result);
                }
            }
        } catch (Exception e) { logger.warn("解析 YAML 失败: {}", file, e); }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> map, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) flattenMap(key, (Map<String, Object>) value, result);
            else if (value != null) result.put(key, value.toString());
        }
    }
}
