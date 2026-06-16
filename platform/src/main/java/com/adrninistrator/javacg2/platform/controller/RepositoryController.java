package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.dto.CloneRequest;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.BytecodeAnalyzer;
import com.adrninistrator.javacg2.platform.service.BuildLogService;
import com.adrninistrator.javacg2.platform.service.impl.ClaudeApiClient;
import com.adrninistrator.javacg2.platform.service.impl.DocGenerator;
import com.adrninistrator.javacg2.platform.service.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/repos")
public class RepositoryController {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryController.class);

    private final RepositoryRepo repositoryRepo;
    private final RepositoryManager repositoryManager;
    private final BytecodeAnalyzer bytecodeAnalyzer;
    private final ExecutorService analysisExecutor;
    private final com.adrninistrator.javacg2.platform.repository.RepoConfigRepo repoConfigRepo;
    private final DocGenerator docGenerator;
    private final ClaudeApiClient claudeClient;
    private final BuildLogService buildLogService;

    public RepositoryController(RepositoryRepo repositoryRepo, RepositoryManager repositoryManager,
                                 BytecodeAnalyzer bytecodeAnalyzer,
                                 @Qualifier("analysisExecutor") ExecutorService analysisExecutor,
                                 com.adrninistrator.javacg2.platform.repository.RepoConfigRepo repoConfigRepo,
                                 DocGenerator docGenerator, ClaudeApiClient claudeClient,
                                 BuildLogService buildLogService) {
        this.repositoryRepo = repositoryRepo;
        this.repositoryManager = repositoryManager;
        this.bytecodeAnalyzer = bytecodeAnalyzer;
        this.analysisExecutor = analysisExecutor;
        this.repoConfigRepo = repoConfigRepo;
        this.docGenerator = docGenerator;
        this.claudeClient = claudeClient;
        this.buildLogService = buildLogService;
    }

    @GetMapping
    public ApiResponse<List<RepositoryEntity>> list() {
        return ApiResponse.ok(repositoryRepo.findAll());
    }

    @PostMapping("/branches")
    public ApiResponse<List<String>> listBranches(@RequestBody CloneRequest request) {
        if (request.gitUrl() == null || request.gitUrl().isBlank()) {
            return ApiResponse.error("VALIDATION_ERROR", "Git URL 不能为空", "请提供有效的 Git 仓库 URL");
        }
        List<String> branches = repositoryManager.listRemoteBranches(request.gitUrl(), request.token(), request.repoType());
        return ApiResponse.ok(branches);
    }

    @PostMapping
    public ApiResponse<RepositoryManager.RepoResult> clone(@RequestBody CloneRequest request) {
        if (request.gitUrl() == null || request.gitUrl().isBlank()) {
            return ApiResponse.error("VALIDATION_ERROR", "Git URL 不能为空", "请提供有效的 Git 仓库 URL");
        }
        RepositoryManager.RepoResult result = repositoryManager.cloneRepository(
                request.gitUrl(), request.token(), request.repoType(), request.branch());

        // 保存包前缀到 repo_config
        if (result.success() && result.repoId() != null && request.packagePrefix() != null && !request.packagePrefix().isBlank()) {
            var entity = repoConfigRepo.findByRepoIdAndConfigKey(result.repoId(), "analyze.package.prefix").orElseGet(() -> {
                var e = new com.adrninistrator.javacg2.platform.entity.RepoConfigEntity();
                e.setRepoId(result.repoId());
                e.setConfigKey("analyze.package.prefix");
                return e;
            });
            entity.setConfigValue(request.packagePrefix());
            entity.setSource("USER");
            entity.setUpdatedAt(java.time.LocalDateTime.now());
            repoConfigRepo.save(entity);
        }

        return ApiResponse.ok(result);
    }

    @PostMapping("/{id}/pull")
    public ApiResponse<RepositoryManager.RepoResult> pull(@PathVariable Long id) {
        return ApiResponse.ok(repositoryManager.pullRepository(id));
    }

    @PostMapping("/{id}/build")
    public ApiResponse<RepositoryManager.RepoResult> build(@PathVariable Long id) {
        return ApiResponse.ok(repositoryManager.triggerBuild(id));
    }

    @PostMapping("/{id}/analyze")
    public ApiResponse<String> analyze(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean forceRebuild) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        String currentStatus = repo.getStatus();
        if ("ANALYZING".equals(currentStatus) || "BUILDING".equals(currentStatus)) {
            return ApiResponse.error("ALREADY_RUNNING", "该仓库正在分析中", "请等待完成或重置状态");
        }
        if ("QUEUED".equals(currentStatus)) {
            return ApiResponse.error("ALREADY_QUEUED", "该仓库已在排队中", "请等待前面的任务完成");
        }
        if ("GENERATING_DOC".equals(currentStatus)) {
            return ApiResponse.error("DOC_GENERATING", "该仓库正在生成文档", "请等待文档生成完成后再分析");
        }

        // 先设为排队状态
        repo.setStatus("QUEUED");
        repositoryRepo.save(repo);

        final boolean rebuild = forceRebuild;
        try {
            analysisExecutor.submit(() -> {
                try {
                    // 开始执行时改为 ANALYZING
                    var r = repositoryRepo.findById(id).orElse(null);
                    if (r != null) {
                        r.setStatus("ANALYZING");
                        repositoryRepo.save(r);
                    }
                    bytecodeAnalyzer.analyzeFullProject(id, rebuild);
                } catch (Exception e) {
                    logger.error("分析异常: repoId={}", id, e);
                }
            });
            return ApiResponse.ok("任务已提交，当前状态：排队中");
        } catch (RejectedExecutionException e) {
            repo.setStatus("READY");
            repositoryRepo.save(repo);
            return ApiResponse.error("QUEUE_FULL", "分析队列已满，请稍后再试", "当前最多排队 10 个任务");
        }
    }

    @PostMapping("/{id}/reset")
    public ApiResponse<String> reset(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        repo.setStatus("READY");
        repositoryRepo.save(repo);
        return ApiResponse.ok("状态已重置");
    }

    @PostMapping("/{id}/rebuild-index")
    public ApiResponse<String> rebuildIndex(@PathVariable Long id) {
        bytecodeAnalyzer.rebuildIndex(id);
        return ApiResponse.ok("索引重建完成");
    }

    @PostMapping("/rebuild-index-all")
    public ApiResponse<String> rebuildIndexAll() {
        List<RepositoryEntity> repos = repositoryRepo.findAll();
        int count = 0;
        for (RepositoryEntity repo : repos) {
            if ("READY".equals(repo.getStatus())) {
                try {
                    bytecodeAnalyzer.rebuildIndex(repo.getId());
                    count++;
                } catch (Exception e) {
                    logger.warn("补建索引失败: repoId={}, {}", repo.getId(), e.getMessage());
                }
            }
        }
        return ApiResponse.ok("已完成 " + count + " 个仓库的索引重建");
    }

    @PostMapping("/{id}/sync")
    public ApiResponse<List<String>> sync(@PathVariable Long id) {
        repositoryManager.pullRepository(id);
        List<String> changes = repositoryManager.detectChanges(id);
        return ApiResponse.ok(changes);
    }

    @GetMapping("/{id}/overview")
    public ApiResponse<String> getOverview(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        return ApiResponse.ok(repo.getOverview());
    }

    @PostMapping("/{id}/regenerate-overview")
    public ApiResponse<String> regenerateOverview(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        if (!claudeClient.isConfigured()) return ApiResponse.error("NOT_CONFIGURED", "请先配置 Claude API", "");
        String currentStatus = repo.getStatus();
        if ("ANALYZING".equals(currentStatus) || "BUILDING".equals(currentStatus) || "QUEUED".equals(currentStatus)) {
            return ApiResponse.error("ALREADY_RUNNING", "该仓库正在分析中", "请等待分析完成后再生成文档");
        }
        if ("GENERATING_DOC".equals(currentStatus)) {
            return ApiResponse.error("DOC_GENERATING", "文档正在生成中", "请等待完成");
        }

        // 设置状态锁
        repo.setStatus("GENERATING_DOC");
        repositoryRepo.save(repo);

        final Long repoId = id;
        new Thread(() -> {
            try {
                logger.info("开始生成 AI 概览文档: repoId={}", repoId);
                buildLogService.clear(repoId);
                buildLogService.append(repoId, "📖 开始生成 AI 概览文档...");
                String overview = docGenerator.generateAIOverview(repoId);
                var r = repositoryRepo.findById(repoId).orElse(null);
                if (r != null) {
                    r.setOverview(overview);
                    r.setStatus("READY");
                    repositoryRepo.save(r);
                }
                buildLogService.finish(repoId, true);
                logger.info("AI 概览文档生成完成: repoId={}, 长度={}", repoId, overview.length());
            } catch (Exception e) {
                logger.error("AI 概览文档生成失败: repoId={}", repoId, e);
                buildLogService.append(repoId, "❌ 文档生成失败: " + e.getMessage());
                buildLogService.finish(repoId, false);
                // 恢复状态
                var r = repositoryRepo.findById(repoId).orElse(null);
                if (r != null) {
                    r.setStatus("READY");
                    repositoryRepo.save(r);
                }
            }
        }).start();

        return ApiResponse.ok("概览文档正在后台生成，请稍后刷新查看");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        repositoryManager.deleteRepository(id);
        return ApiResponse.ok("已删除");
    }

    @GetMapping("/{id}/jars")
    public ApiResponse<List<Map<String, String>>> listJars(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");

        // 读取 manifest 文件获取来源标签
        Map<String, String> manifest = new java.util.LinkedHashMap<>();
        Path manifestFile = Path.of(repo.getLocalPath(), "_collected_jars", "_manifest.txt");
        if (Files.exists(manifestFile)) {
            try {
                for (String line : Files.readAllLines(manifestFile)) {
                    line = line.trim();
                    if (line.startsWith("[上传]")) {
                        manifest.put(line.substring(5).trim(), "上传");
                    } else if (line.startsWith("[编译]")) {
                        manifest.put(line.substring(5).trim(), "编译");
                    }
                }
            } catch (IOException ignored) {}
        }

        // 列出 _collected_jars 目录（最终用于分析的）
        List<Map<String, String>> jars = new ArrayList<>();
        Path collectedDir = Path.of(repo.getLocalPath(), "_collected_jars");
        if (Files.isDirectory(collectedDir)) {
            try (Stream<Path> stream = Files.list(collectedDir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return (name.endsWith(".jar") || name.endsWith(".war")) && !name.startsWith("_");
                }).sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    String source = manifest.getOrDefault(name, "编译");
                    long size = 0;
                    try { size = Files.size(p); } catch (IOException ignored) {}
                    jars.add(Map.of("name", name, "source", source, "size", String.valueOf(size / 1024)));
                });
            } catch (IOException ignored) {}
        }

        // 也列出 uploaded_jars 中未合并的
        Path uploadedDir = Path.of(repo.getLocalPath(), "uploaded_jars");
        if (Files.isDirectory(uploadedDir)) {
            try (Stream<Path> stream = Files.list(uploadedDir)) {
                stream.filter(p -> p.toString().endsWith(".jar") || p.toString().endsWith(".war"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        boolean alreadyInCollected = jars.stream().anyMatch(j -> j.get("name").equals(name));
                        if (!alreadyInCollected) {
                            long size = 0;
                            try { size = Files.size(p); } catch (IOException ignored) {}
                            jars.add(Map.of("name", name, "source", "上传(待合并)", "size", String.valueOf(size / 1024)));
                        }
                    });
            } catch (IOException ignored) {}
        }

        return ApiResponse.ok(jars);
    }
}
