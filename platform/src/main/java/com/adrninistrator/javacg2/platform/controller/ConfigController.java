package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.entity.SystemConfigEntity;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final SystemConfigRepo configRepo;
    private final com.adrninistrator.javacg2.platform.service.PromptService promptService;

    public ConfigController(SystemConfigRepo configRepo,
                            com.adrninistrator.javacg2.platform.service.PromptService promptService) {
        this.configRepo = configRepo;
        this.promptService = promptService;
    }

    /** 列出所有 AI Prompt 定义与当前值（含默认值与用户覆盖），供配置界面渲染 */
    @GetMapping("/prompts")
    public ApiResponse<java.util.List<Map<String, Object>>> getPrompts() {
        return ApiResponse.ok(promptService.listWithValues());
    }

    /**
     * 保存单个 Prompt 覆盖值。body: {key, value}。
     * value 为空 → 恢复默认（删除覆盖）。
     */
    @PutMapping("/prompts")
    public ApiResponse<String> savePrompt(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            return ApiResponse.error("BAD_REQUEST", "缺少 key", "请传入 prompt 的 key");
        }
        boolean ok = promptService.saveOverride(key, body.get("value"));
        return ok ? ApiResponse.ok("保存成功")
                  : ApiResponse.error("UNKNOWN_KEY", "未知 prompt key: " + key, "请刷新页面重试");
    }

    @GetMapping("/maven")
    public ApiResponse<Map<String, String>> getMavenConfig() {
        Map<String, String> config = new HashMap<>();
        configRepo.findByConfigKey("maven.settings.path").ifPresent(c -> config.put("settingsPath", c.getConfigValue()));
        configRepo.findByConfigKey("maven.repo.local").ifPresent(c -> config.put("localRepo", c.getConfigValue()));
        configRepo.findByConfigKey("maven.home").ifPresent(c -> config.put("mavenHome", c.getConfigValue()));
        configRepo.findByConfigKey("build.java.home").ifPresent(c -> config.put("javaHome", c.getConfigValue()));
        return ApiResponse.ok(config);
    }

    @PutMapping("/maven")
    public ApiResponse<String> saveMavenConfig(@RequestBody Map<String, String> config) {
        saveConfig("maven.settings.path", config.get("settingsPath"));
        saveConfig("maven.repo.local", config.get("localRepo"));
        saveConfig("maven.home", config.get("mavenHome"));
        saveConfig("build.java.home", config.get("javaHome"));
        return ApiResponse.ok("保存成功");
    }

    @GetMapping("/git")
    public ApiResponse<Map<String, String>> getGitConfig() {
        Map<String, String> config = new HashMap<>();
        configRepo.findByConfigKey("git.default.repo.type").ifPresent(c -> config.put("repoType", c.getConfigValue()));
        configRepo.findByConfigKey("git.default.token").ifPresent(c -> config.put("token", c.getConfigValue()));
        return ApiResponse.ok(config);
    }

    @PutMapping("/git")
    public ApiResponse<String> saveGitConfig(@RequestBody Map<String, String> config) {
        if (config.get("token") != null && !config.get("token").isBlank()) {
            saveConfig("git.default.token", config.get("token"));
        }
        saveConfig("git.default.repo.type", config.getOrDefault("repoType", "GITLAB"));
        return ApiResponse.ok("保存成功");
    }

    @GetMapping("/claude")
    public ApiResponse<Map<String, String>> getClaudeConfig() {
        Map<String, String> config = new HashMap<>();
        configRepo.findByConfigKey("claude.api.url").ifPresent(c -> config.put("apiUrl", c.getConfigValue()));
        configRepo.findByConfigKey("claude.api.key").ifPresent(c ->
                config.put("apiKeyConfigured", (c.getConfigValue() != null && !c.getConfigValue().isBlank()) ? "true" : "false"));
        configRepo.findByConfigKey("claude.system.prompt").ifPresent(c -> config.put("systemPrompt", c.getConfigValue()));
        config.put("defaultPrompt", com.adrninistrator.javacg2.platform.service.impl.QAEngineImpl.DEFAULT_SYSTEM_PROMPT);
        return ApiResponse.ok(config);
    }

    @PutMapping("/claude")
    public ApiResponse<String> saveClaudeConfig(@RequestBody Map<String, String> config) {
        saveConfig("claude.api.url", config.get("apiUrl"));
        if (config.get("apiKey") != null && !config.get("apiKey").isBlank()) {
            saveConfig("claude.api.key", config.get("apiKey"));
        }
        if (config.containsKey("systemPrompt")) {
            saveConfig("claude.system.prompt", config.get("systemPrompt"));
        }
        return ApiResponse.ok("保存成功");
    }

    @GetMapping("/analyze")
    public ApiResponse<Map<String, String>> getAnalyzeConfig() {
        Map<String, String> config = new HashMap<>();
        configRepo.findByConfigKey("analyze.package.prefix").ifPresent(c -> config.put("packagePrefix", c.getConfigValue()));
        return ApiResponse.ok(config);
    }

    @PutMapping("/analyze")
    public ApiResponse<String> saveAnalyzeConfig(@RequestBody Map<String, String> config) {
        saveConfig("analyze.package.prefix", config.get("packagePrefix"));
        return ApiResponse.ok("保存成功");
    }

    @GetMapping("/embedding")
    public ApiResponse<Map<String, String>> getEmbeddingConfig() {
        Map<String, String> config = new HashMap<>();
        configRepo.findByConfigKey("embedding.api.url").ifPresent(c -> config.put("apiUrl", c.getConfigValue()));
        configRepo.findByConfigKey("embedding.api.key").ifPresent(c ->
                config.put("apiKeyConfigured", (c.getConfigValue() != null && !c.getConfigValue().isBlank()) ? "true" : "false"));
        configRepo.findByConfigKey("embedding.model").ifPresent(c -> config.put("model", c.getConfigValue()));
        configRepo.findByConfigKey("qdrant.url").ifPresent(c -> config.put("qdrantUrl", c.getConfigValue()));
        return ApiResponse.ok(config);
    }

    @PutMapping("/embedding")
    public ApiResponse<String> saveEmbeddingConfig(@RequestBody Map<String, String> config) {
        saveConfig("embedding.api.url", config.get("apiUrl"));
        if (config.get("apiKey") != null && !config.get("apiKey").isBlank()) {
            saveConfig("embedding.api.key", config.get("apiKey"));
        }
        saveConfig("embedding.model", config.get("model"));
        saveConfig("qdrant.url", config.get("qdrantUrl"));
        return ApiResponse.ok("保存成功");
    }

    private void saveConfig(String key, String value) {
        if (value == null) return;
        SystemConfigEntity entity = configRepo.findByConfigKey(key).orElseGet(() -> {
            SystemConfigEntity e = new SystemConfigEntity();
            e.setConfigKey(key);
            return e;
        });
        entity.setConfigValue(value);
        entity.setUpdatedAt(LocalDateTime.now());
        configRepo.save(entity);
    }
}
