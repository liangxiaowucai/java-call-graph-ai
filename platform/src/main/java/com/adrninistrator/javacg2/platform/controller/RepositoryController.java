package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.dto.CloneRequest;
import com.adrninistrator.javacg2.platform.dto.RepositoryDetailDTO;
import com.adrninistrator.javacg2.platform.dto.RepositoryListDTO;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/repos")
@CrossOrigin(originPatterns = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.PUT})
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
    private final ChunkRepo chunkRepo;
    private final com.adrninistrator.javacg2.platform.repository.CallGraphRepo callGraphRepo;
    private final com.adrninistrator.javacg2.platform.service.RepoDataStore repoDataStore;

    public RepositoryController(RepositoryRepo repositoryRepo, RepositoryManager repositoryManager,
                                 BytecodeAnalyzer bytecodeAnalyzer,
                                 @Qualifier("analysisExecutor") ExecutorService analysisExecutor,
                                 com.adrninistrator.javacg2.platform.repository.RepoConfigRepo repoConfigRepo,
                                 DocGenerator docGenerator, ClaudeApiClient claudeClient,
                                 BuildLogService buildLogService, ChunkRepo chunkRepo,
                                 com.adrninistrator.javacg2.platform.repository.CallGraphRepo callGraphRepo,
                                 com.adrninistrator.javacg2.platform.service.RepoDataStore repoDataStore) {
        this.repositoryRepo = repositoryRepo;
        this.repositoryManager = repositoryManager;
        this.bytecodeAnalyzer = bytecodeAnalyzer;
        this.analysisExecutor = analysisExecutor;
        this.repoConfigRepo = repoConfigRepo;
        this.docGenerator = docGenerator;
        this.claudeClient = claudeClient;
        this.buildLogService = buildLogService;
        this.chunkRepo = chunkRepo;
        this.callGraphRepo = callGraphRepo;
        this.repoDataStore = repoDataStore;
    }

    @GetMapping
    public ApiResponse<List<RepositoryListDTO>> list() {
        List<RepositoryEntity> entities = repositoryRepo.findAll();
        List<RepositoryListDTO> dtos = entities.stream()
                .map(RepositoryListDTO::fromEntity)
                .toList();
        return ApiResponse.ok(dtos);
    }

    @GetMapping("/{id}")
    public ApiResponse<RepositoryDetailDTO> getDetail(@PathVariable Long id) {
        RepositoryEntity entity = repositoryRepo.findById(id).orElse(null);
        if (entity == null) {
            return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        }
        return ApiResponse.ok(RepositoryDetailDTO.fromEntity(entity));
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

        if (result.success() && result.repoId() != null) {
            // 保存包前缀到 repo_config
            if (request.packagePrefix() != null && !request.packagePrefix().isBlank()) {
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
            
            // 保存 URL 路径标识符到 repository 表
            if (request.urlPathIdentifier() != null && !request.urlPathIdentifier().isBlank()) {
                var repo = repositoryRepo.findById(result.repoId()).orElse(null);
                if (repo != null) {
                    repo.setUrlPathIdentifier(request.urlPathIdentifier());
                    repositoryRepo.save(repo);
                }
            }
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

    @PatchMapping("/{id}")
    public ApiResponse<RepositoryListDTO> updateRepository(@PathVariable Long id, @RequestBody Map<String, String> updates) {
        RepositoryEntity repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) {
            return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        }

        // 更新仓库名称
        if (updates.containsKey("name")) {
            String name = updates.get("name");
            if (name != null && !name.trim().isEmpty()) {
                repo.setName(name.trim());
                logger.info("[仓库更新] 仓库 {} 的名称已更新", repo.getName());
            }
        }

        // 更新 Git URL
        if (updates.containsKey("gitUrl")) {
            String gitUrl = updates.get("gitUrl");
            if (gitUrl != null && !gitUrl.trim().isEmpty()) {
                repo.setGitUrl(gitUrl.trim());
                logger.info("[仓库更新] 仓库 {} 的 Git URL 已更新", repo.getName());
            }
        }

        // 更新分支
        if (updates.containsKey("branch")) {
            String branch = updates.get("branch");
            if (branch != null && !branch.trim().isEmpty()) {
                repo.setBranch(branch.trim());
                logger.info("[仓库更新] 仓库 {} 的分支已更新为: {}", repo.getName(), repo.getBranch());
            }
        }

        // 更新包前缀配置
        if (updates.containsKey("packagePrefix")) {
            String packagePrefix = updates.get("packagePrefix");
            if (packagePrefix != null && !packagePrefix.trim().isEmpty()) {
                var entity = repoConfigRepo.findByRepoIdAndConfigKey(id, "analyze.package.prefix").orElseGet(() -> {
                    var e = new com.adrninistrator.javacg2.platform.entity.RepoConfigEntity();
                    e.setRepoId(id);
                    e.setConfigKey("analyze.package.prefix");
                    return e;
                });
                entity.setConfigValue(packagePrefix.trim());
                entity.setSource("USER");
                entity.setUpdatedAt(java.time.LocalDateTime.now());
                repoConfigRepo.save(entity);
                logger.info("[仓库更新] 仓库 {} 的包前缀已更新为: {}", repo.getName(), packagePrefix.trim());
            }
        }

        // 更新 URL 路径标识符（支持多个，逗号分隔）
        if (updates.containsKey("urlPathIdentifier")) {
            String urlPathIdentifier = updates.get("urlPathIdentifier");
            repo.setUrlPathIdentifier(urlPathIdentifier == null || urlPathIdentifier.trim().isEmpty() ? null : urlPathIdentifier.trim());
            logger.info("[仓库更新] 仓库 {} 的 URL 路径标识符已更新为: {}", repo.getName(), repo.getUrlPathIdentifier());
        }

        repositoryRepo.save(repo);
        return ApiResponse.ok(RepositoryListDTO.fromEntity(repo));
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

    /**
     * 获取仓库的类间调用关系（用于文件关系图的边）。
     * 从 call_graph 表聚合：caller 类名 → callee 类名（去重），只返回两端都在本仓库业务包内的边。
     */
    @GetMapping("/{id}/class-edges")
    public ApiResponse<List<Map<String, String>>> getClassEdges(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");

        // 读取包前缀
        String prefixRaw = repoConfigRepo.findByRepoIdAndConfigKey(id, "analyze.package.prefix")
                .map(c -> c.getConfigValue())
                .orElse(null);
        List<String> prefixes = new java.util.ArrayList<>();
        if (prefixRaw != null) {
            for (String p : prefixRaw.split("[,;\\s]+")) {
                String t = p.trim(); if (!t.isEmpty()) prefixes.add(t);
            }
        }

        // 聚合类间调用（去重）
        Set<String> seen = new java.util.HashSet<>();
        List<Map<String, String>> edges = new java.util.ArrayList<>();

        // Use memory cache instead of DB query
        var repoData = repoDataStore.get(id);
        for (var cg : repoData.callGraphMap.values().stream().flatMap(java.util.Collection::stream).collect(java.util.stream.Collectors.toList())) {
            if (cg.getEnabled() == null || !cg.getEnabled()) continue;
            if ("EXTENDS".equals(cg.getCallType()) || "IMPLEMENTS".equals(cg.getCallType())) continue;

            String callerFull = cg.getCallerMethod();
            String calleeFull = cg.getCalleeMethod();
            int c1 = callerFull.lastIndexOf(':');
            int c2 = calleeFull.lastIndexOf(':');
            if (c1 <= 0 || c2 <= 0) continue;

            String callerClass = callerFull.substring(0, c1);
            String calleeClass = calleeFull.substring(0, c2);

            // 同类调用跳过
            if (callerClass.equals(calleeClass)) continue;
            // 跳过匿名内部类
            if (callerClass.matches(".*\\$\\d+$") || calleeClass.matches(".*\\$\\d+$")) continue;
            // 只保留业务包内的边
            if (!prefixes.isEmpty()) {
                if (prefixes.stream().noneMatch(callerClass::startsWith)) continue;
                if (prefixes.stream().noneMatch(calleeClass::startsWith)) continue;
            }
            // 跳过 gRPC/proto 生成类
            if (com.adrninistrator.javacg2.platform.util.GrpcNoiseFilter.isGrpcNoiseClass(callerClass)) continue;
            if (com.adrninistrator.javacg2.platform.util.GrpcNoiseFilter.isGrpcNoiseClass(calleeClass)) continue;

            String key = callerClass + ">" + calleeClass;
            if (seen.add(key)) {
                edges.add(Map.of("source", callerClass, "target", calleeClass));
            }
        }

        return ApiResponse.ok(edges);
    }

    /**
     * 获取仓库的类/文件树结构（从 chunks 表聚合，只返回业务代码）。
     * 过滤规则：
     *  1. 包前缀匹配（业务代码）
     *  2. 过滤 gRPC/protobuf 生成类（GrpcNoiseFilter）
     *  3. 过滤 $Builder / OrBuilder / 匿名内部类
     *  4. 过滤 .api.client.model / .api.client.service 包（proto 生成）
     */
    @GetMapping("/{id}/file-tree")
    public ApiResponse<List<Map<String, Object>>> getFileTree(@PathVariable Long id) {
        var repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");

        // 读取用户配置的包前缀，只展示属于本项目的业务类
        String prefixRaw = repoConfigRepo.findByRepoIdAndConfigKey(id, "analyze.package.prefix")
                .map(c -> c.getConfigValue())
                .orElse(null);
        List<String> packagePrefixes = new java.util.ArrayList<>();
        if (prefixRaw != null && !prefixRaw.isBlank()) {
            for (String p : prefixRaw.split("[,;\\s]+")) {
                String t = p.trim();
                if (!t.isEmpty()) packagePrefixes.add(t);
            }
        }

        // 从 chunks 数据推断每个 jarNum 的模块名
        // 策略：按 jarNum 分组，取该组类名中最常见的顶层包段作为模块名
        Map<Integer, String> jarNameMap = new java.util.HashMap<>();
        Map<Integer, Map<String, Integer>> jarPkgCount = new java.util.HashMap<>();
        for (ChunkEntity chunk : repoDataStore.get(id).chunkMap.values()) {
            Integer jn = chunk.getJarNum();
            if (jn == null) continue;
            String cn = chunk.getClassName();
            if (cn == null) continue;
            // 取类名的倒数第2段（通常是 module 特征段，如 tla.base / tla.consumer）
            String[] segs = cn.split("\\.");
            String key = segs.length >= 4 ? segs[segs.length - 3] + "." + segs[segs.length - 2] : (segs.length >= 3 ? segs[segs.length - 2] : cn);
            jarPkgCount.computeIfAbsent(jn, k -> new java.util.HashMap<>()).merge(key, 1, Integer::sum);
        }
        jarPkgCount.forEach((jn, pkgMap) -> {
            // 取出现最多的包段作为模块名
            String bestPkg = pkgMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("module-" + jn);
            jarNameMap.put(jn, bestPkg);
        });

        Map<String, Map<String, Object>> classMap = new java.util.LinkedHashMap<>();
        for (ChunkEntity chunk : repoDataStore.get(id).chunkMap.values()) {
            String cn = chunk.getClassName();
            if (cn == null || cn.isBlank()) continue;

            // 如果配置了包前缀，只显示前缀匹配的类（本项目业务代码）
            if (!packagePrefixes.isEmpty() && packagePrefixes.stream().noneMatch(cn::startsWith)) continue;

            // 跳过匿名内部类 $1, $2（目录结构不展示，但调用链保留）
            if (cn.matches(".*\\$\\d+$")) continue;
            // 跳过所有命名内部类（$ChapterStat 等，属于外部类的一部分）
            if (cn.contains("$")) continue;
            // 跳过 gRPC 生成类（工厂类、Stub、MethodHandlers 等）
            if (com.adrninistrator.javacg2.platform.util.GrpcNoiseFilter.isGrpcNoiseClass(cn)) continue;
            // 跳过 proto 生成包
            String pkg = chunk.getPackageName() != null ? chunk.getPackageName() : "";
            if (pkg.contains(".api.client.model") || pkg.contains(".api.client.service")
                    || pkg.contains(".api.grpc.model") || pkg.contains(".proto.")) continue;

            // 用 className + jarNum 作为唯一键，区分多 module 同名类
            String uniqueKey = cn + "#" + (chunk.getJarNum() != null ? chunk.getJarNum() : 0);
            final String finalPkg = pkg;
            final Integer jarNum = chunk.getJarNum();
            final String jarName = jarNameMap.getOrDefault(jarNum != null ? jarNum : 0, "module-" + (jarNum != null ? jarNum : 0));
            classMap.compute(uniqueKey, (k, v) -> {
                if (v == null) {
                    v = new java.util.LinkedHashMap<>();
                    v.put("className", cn);
                    v.put("packageName", finalPkg);
                    v.put("filePath", chunk.getFilePath());
                    v.put("jarNum", jarNum != null ? jarNum : 0);
                    v.put("jarName", jarName);
                    v.put("methodCount", 0);
                }
                v.put("methodCount", (int) v.get("methodCount") + 1);
                return v;
            });
        }

        return ApiResponse.ok(new ArrayList<>(classMap.values()));
    }
}
