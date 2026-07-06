package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.impl.ClaudeApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上线文档生成服务。
 * 结合 git diff + 调用链分析数据，生成四视角版本发布报告：
 * 运维操作清单 / 开发变更详情 / 架构影响分析 / 评审要点
 */
@Service
public class ReleaseDocService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseDocService.class);

    private final RepositoryRepo repositoryRepo;
    private final ClaudeApiClient claudeClient;
    private final RepoDataStore repoDataStore;
    private final PromptService promptService;

    public ReleaseDocService(RepositoryRepo repositoryRepo, ClaudeApiClient claudeClient,
                              RepoDataStore repoDataStore, PromptService promptService) {
        this.repositoryRepo = repositoryRepo;
        this.claudeClient = claudeClient;
        this.repoDataStore = repoDataStore;
        this.promptService = promptService;
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    public record RepoSelection(Long repoId, String branch, String baseBranch) {}

    public record ReleaseDoc(
        String markdown,
        List<ConfigChange> configChanges,
        List<SqlChange> sqlChanges,
        List<ApiChange> apiChanges,
        List<DependencyChange> dependencyChanges,
        List<DeployChange> deployChanges,
        List<CommitInfo> commits,
        List<FileChange> otherChanges
    ) {}

    public record ConfigChange(String repo, String file, String changeType, String key, String value) {}
    public record SqlChange(String repo, String file, String operation, String tableName, String detail) {}
    public record ApiChange(String repo, String file, String method, String path, String changeType) {}
    public record DependencyChange(String repo, String file, String artifact, String fromVersion, String toVersion) {}
    public record DeployChange(String repo, String file, String changeType, String detail) {}
    public record CommitInfo(String repo, String hash, String message, String author, String date) {}
    public record FileChange(String repo, String file, String status, int additions, int deletions) {}

    /** 单个 Java 类的变更分析结果（来自 git diff + 调用链数据的交叉引用） */
    public record CodeChangeAnalysis(
        String repo,
        String className,      // 全限定类名
        String filePath,       // git 路径
        String status,         // 新增/修改/删除
        String category,       // CONTROLLER/SERVICE/MAPPER/MQ/CACHE/CONFIG/OTHER
        List<String> endpointSummaries,  // 该类含的接口 (method + url)
        List<String> callerSummaries,    // 直接调用方（上游）
        List<String> boundarySummaries,  // 触及的外部边界 (DB/MQ/Redis/HTTP)
        int additions,
        int deletions
    ) {}

    /** 跨仓库关联风险推断 */
    public record CrossRepoRisk(
        String sourceRepo,
        String targetRepo,
        String riskType,   // API_CHANGE / SCHEMA_CHANGE / CONFIG_CHANGE
        String detail
    ) {}

    // ── 主入口 ────────────────────────────────────────────────────────────────

    public ReleaseDoc generate(List<RepoSelection> selections) {
        log.info("[ReleaseDoc] 开始生成上线文档, 仓库数: {}", selections.size());

        List<ConfigChange>     configChanges     = new ArrayList<>();
        List<SqlChange>        sqlChanges        = new ArrayList<>();
        List<ApiChange>        apiChanges        = new ArrayList<>();
        List<DependencyChange> dependencyChanges = new ArrayList<>();
        List<DeployChange>     deployChanges     = new ArrayList<>();
        List<CommitInfo>       commits           = new ArrayList<>();
        List<FileChange>       otherChanges      = new ArrayList<>();
        List<CodeChangeAnalysis> codeChanges     = new ArrayList<>();

        for (RepoSelection sel : selections) {
            RepositoryEntity repo = repositoryRepo.findById(sel.repoId()).orElse(null);
            if (repo == null) continue;

            String repoName   = repo.getName();
            String localPath  = repo.getLocalPath();
            String branch     = sel.branch();
            String baseBranch = sel.baseBranch() != null ? sel.baseBranch() : "master";

            if (!branchExists(localPath, branch)) {
                log.warn("[ReleaseDoc] 分支不存在: {} in {}", branch, repoName);
                continue;
            }

            List<String[]> changedFiles = getChangedFiles(localPath, baseBranch, branch);
            log.info("[ReleaseDoc] 仓库 {} 对比 {}...{}，变更文件 {} 个", repoName, baseBranch, branch, changedFiles.size());

            for (String[] fileInfo : changedFiles) {
                String status   = fileInfo[0];
                String filePath = fileInfo[1];

                if (filePath.contains(".claude/") || filePath.contains(".uai/")
                    || filePath.contains(".harness/") || filePath.contains("openspec/")
                    || (filePath.startsWith(".") && !filePath.startsWith(".gitlab"))) continue;

                String statusLabel = "A".equals(status) ? "新增" : "D".equals(status) ? "删除" : "修改";

                if (isConfigFile(filePath)) {
                    configChanges.addAll(parseConfigDiff(localPath, repoName, filePath, baseBranch, branch));
                } else if (isSqlFile(filePath)) {
                    sqlChanges.addAll(parseSqlDiff(localPath, repoName, filePath, baseBranch, branch));
                } else if (isDependencyFile(filePath)) {
                    dependencyChanges.addAll(parseDependencyDiff(localPath, repoName, filePath, baseBranch, branch));
                } else if (isDeployFile(filePath)) {
                    String diff = getFileDiff(localPath, filePath, baseBranch, branch);
                    deployChanges.add(new DeployChange(repoName, filePath, statusLabel, summarizeDiff(diff)));
                } else if (isApiFile(filePath)) {
                    apiChanges.addAll(parseApiDiff(localPath, repoName, filePath, baseBranch, branch));
                }

                if (filePath.endsWith(".java") && !filePath.contains("test/")) {
                    int[] stats = getDiffStats(localPath, filePath, baseBranch, branch);
                    otherChanges.add(new FileChange(repoName, filePath, statusLabel, stats[0], stats[1]));
                    CodeChangeAnalysis analysis = analyzeCodeChange(
                        repoName, filePath, statusLabel, sel.repoId(), localPath, baseBranch, branch);
                    if (analysis != null) codeChanges.add(analysis);
                } else if (!filePath.contains(".claude/") && !filePath.contains(".uai/")
                        && !filePath.contains("docs/") && !filePath.startsWith(".")
                        && !filePath.endsWith(".md") && !filePath.endsWith(".txt")
                        && !filePath.endsWith(".java")) {
                    int[] stats = getDiffStats(localPath, filePath, baseBranch, branch);
                    otherChanges.add(new FileChange(repoName, filePath, statusLabel, stats[0], stats[1]));
                }
            }
        }

        // 跨仓库关联风险推断
        List<CrossRepoRisk> crossRepoRisks = inferCrossRepoRisks(selections, apiChanges, sqlChanges, configChanges);
        log.info("[ReleaseDoc] 变更汇总：配置 {} / SQL {} / 接口 {} / 依赖 {} / 部署 {} / 代码类 {} / 其他文件 {} / 跨库风险 {}",
                configChanges.size(), sqlChanges.size(), apiChanges.size(), dependencyChanges.size(),
                deployChanges.size(), codeChanges.size(), otherChanges.size(), crossRepoRisks.size());

        log.info("[ReleaseDoc] 开始生成 Markdown（含 AI 摘要）...");
        String markdown = buildMarkdown(selections, configChanges, sqlChanges, apiChanges,
                dependencyChanges, deployChanges, commits, otherChanges, codeChanges, crossRepoRisks);
        log.info("[ReleaseDoc] 上线文档生成完成，markdown 长度 {} 字符", markdown.length());

        return new ReleaseDoc(markdown, configChanges, sqlChanges, apiChanges,
                dependencyChanges, deployChanges, commits, otherChanges);
    }

    /** 单个文件的 git unified diff（供前端「详情」左右对比按需拉取） */
    public record FileDiffResult(String repo, String path, String diff, int additions, int deletions) {}

    /**
     * 按需获取单个变更文件的 unified diff（origin/base...origin/branch）。
     * 供前端点击「详情」时懒加载，渲染左右源码对比。
     */
    public FileDiffResult fileDiff(Long repoId, String filePath, String baseBranch, String branch) {
        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) {
            throw new IllegalArgumentException("仓库不存在: " + repoId);
        }
        String localPath = repo.getLocalPath();
        String base = (baseBranch != null && !baseBranch.isBlank()) ? baseBranch : "master";
        String diff = getFileDiff(localPath, filePath, base, branch);
        int[] stats = getDiffStats(localPath, filePath, base, branch);
        log.info("[ReleaseDoc] 取文件 diff: {} ({}...{})，+{}/-{}", filePath, base, branch, stats[0], stats[1]);
        return new FileDiffResult(repo.getName(), filePath, diff, stats[0], stats[1]);
    }

    // ── 代码变更分析（git diff 路径 × 调用链数据）────────────────────────────

    /** 对单个变更的 Java 文件，交叉查询 RepoDataStore 得出影响分析 */
    private CodeChangeAnalysis analyzeCodeChange(String repoName, String filePath,
            String status, Long repoId, String localPath, String baseBranch, String branch) {
        // 从路径提取全限定类名
        String className = extractClassName(filePath);
        if (className == null) return null;

        String category = classifyFile(filePath);
        int[] stats = getDiffStats(localPath, filePath, baseBranch, branch);

        RepoDataStore.RepoData cache;
        try {
            cache = repoDataStore.get(repoId);
        } catch (Exception e) {
            return new CodeChangeAnalysis(repoName, className, filePath, status, category,
                List.of(), List.of(), List.of(), stats[0], stats[1]);
        }

        // 1. 该类含有的 API 接口
        List<String> endpointSummaries = cache.endpointMap().values().stream()
            .filter(ep -> className.equals(ep.getClassName()))
            .map(ep -> (ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType())
                       + " " + (ep.getUrlPath() != null ? ep.getUrlPath() : ep.getFullMethod()))
            .collect(Collectors.toList());

        // 2. 直接调用方（通过 calleeIndex 反查：谁调用了这个类的任意方法）
        Set<String> callerClassNames = new LinkedHashSet<>();
        cache.calleeIndex().entrySet().stream()
            .filter(e -> e.getKey().startsWith(className + ":"))
            .forEach(e -> e.getValue().stream()
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .map(c -> {
                    int colon = c.getCallerMethod().lastIndexOf(':');
                    return colon > 0 ? c.getCallerMethod().substring(0, colon) : c.getCallerMethod();
                })
                .filter(cls -> !cls.equals(className))
                .forEach(callerClassNames::add));
        List<String> callerSummaries = callerClassNames.stream()
            .map(cls -> cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls)
            .distinct().limit(10).collect(Collectors.toList());

        // 3. 外部边界（DB/MQ/Redis/HTTP）
        List<String> boundarySummaries = cache.boundaryMap().entrySet().stream()
            .filter(e -> e.getKey().startsWith(className + ":"))
            .flatMap(e -> e.getValue().stream())
            .filter(b -> b.getBoundaryType() != null)
            .map(b -> b.getBoundaryType() + (b.getContext() != null ? "(" + truncate(b.getContext(), 50) + ")" : ""))
            .distinct().limit(10).collect(Collectors.toList());

        return new CodeChangeAnalysis(repoName, className, filePath, status, category,
            endpointSummaries, callerSummaries, boundarySummaries, stats[0], stats[1]);
    }

    /** 从文件路径提取全限定类名 */
    private String extractClassName(String filePath) {
        if (!filePath.endsWith(".java")) return null;
        int javaIdx = filePath.indexOf("src/main/java/");
        if (javaIdx < 0) javaIdx = filePath.indexOf("src/main/kotlin/");
        if (javaIdx < 0) return null;
        String relative = filePath.substring(javaIdx);
        relative = relative.replaceFirst("src/main/(java|kotlin)/", "");
        return relative.replace('/', '.').replace(".java", "").replace(".kt", "");
    }

    /** 类别识别 */
    private String classifyFile(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("controller"))                                  return "CONTROLLER";
        if (lower.contains("listener") || lower.contains("consumer"))     return "MQ";
        if (lower.contains("mapper") || lower.contains("repository")
            || lower.contains("dao"))                                      return "MAPPER";
        if (lower.contains("serviceimpl") || lower.contains("service"))   return "SERVICE";
        if (lower.contains("config"))                                      return "CONFIG";
        return "OTHER";
    }

    // ── 跨仓库关联推断 ────────────────────────────────────────────────────────

    private List<CrossRepoRisk> inferCrossRepoRisks(List<RepoSelection> selections,
            List<ApiChange> apiChanges, List<SqlChange> sqlChanges, List<ConfigChange> configChanges) {

        List<CrossRepoRisk> risks = new ArrayList<>();
        Set<Long> selectedIds = selections.stream().map(RepoSelection::repoId).collect(Collectors.toSet());

        // 获取所有已分析的其他仓库
        List<RepositoryEntity> otherRepos = repositoryRepo.findAll().stream()
            .filter(r -> !selectedIds.contains(r.getId())
                      && ("READY".equals(r.getStatus()) || "ANALYZED".equals(r.getStatus())))
            .collect(Collectors.toList());

        if (otherRepos.isEmpty()) return risks;

        // 本次变动的 API URL 集合
        Set<String> changedUrls = apiChanges.stream()
            .filter(a -> a.path() != null)
            .map(ApiChange::path).collect(Collectors.toSet());

        // 本次变动的表名集合（从 SQL 中提取）
        Set<String> changedTables = sqlChanges.stream()
            .map(SqlChange::tableName)
            .filter(t -> t != null && !t.isBlank() && !"SQL变更".equals(t))
            .map(String::toLowerCase).collect(Collectors.toSet());

        // 本次有改动的 repo 名集合
        Set<String> srcRepoNames = selections.stream()
            .map(s -> { RepositoryEntity r = repositoryRepo.findById(s.repoId()).orElse(null);
                        return r != null ? r.getName() : null; })
            .filter(Objects::nonNull).collect(Collectors.toSet());

        for (RepositoryEntity other : otherRepos) {
            try {
                RepoDataStore.RepoData cache = repoDataStore.get(other.getId());

                // 1. API 变更：检查其他仓库的 HTTP 边界是否调用了变动的 URL
                if (!changedUrls.isEmpty()) {
                    cache.boundaryMap().values().stream().flatMap(List::stream)
                        .filter(b -> "HTTP".equals(b.getBoundaryType()) && b.getContext() != null)
                        .forEach(b -> changedUrls.stream()
                            .filter(url -> b.getContext().contains(url))
                            .forEach(url -> risks.add(new CrossRepoRisk(
                                String.join("/", srcRepoNames), other.getName(),
                                "API_CHANGE",
                                "本次修改了接口 `" + url + "`，" + other.getName() +
                                " 有 HTTP 调用记录，请确认是否需要联动更新"))));
                }

                // 2. SQL 变更：检查其他仓库是否有对这些表的 DB 边界
                if (!changedTables.isEmpty()) {
                    cache.boundaryMap().values().stream().flatMap(List::stream)
                        .filter(b -> "DB".equals(b.getBoundaryType()) && b.getContext() != null)
                        .forEach(b -> changedTables.stream()
                            .filter(t -> b.getContext().toLowerCase().contains(t))
                            .findFirst().ifPresent(t -> {
                                if (risks.stream().noneMatch(r ->
                                    r.targetRepo().equals(other.getName()) && r.detail().contains(t))) {
                                    risks.add(new CrossRepoRisk(
                                        String.join("/", srcRepoNames), other.getName(),
                                        "SCHEMA_CHANGE",
                                        "表 `" + t + "` 有 SQL 变更，" + other.getName() +
                                        " 也有对此表的 DB 操作，请确认兼容性"));
                                }
                            }));
                }

                // 3. 配置变更：URL/host/port 类配置变化可能影响其他仓库连接
                configChanges.stream()
                    .filter(c -> { String k = c.key().toLowerCase();
                                   return k.contains("url") || k.contains("host") || k.contains("port"); })
                    .filter(c -> "修改".equals(c.changeType()))
                    .limit(3)
                    .forEach(c -> risks.add(new CrossRepoRisk(
                        String.join("/", srcRepoNames), other.getName(),
                        "CONFIG_CHANGE",
                        "配置项 `" + c.key() + "` 已修改，如其他服务依赖此地址/端口，请确认 " +
                        other.getName() + " 的配置是否同步")));

            } catch (Exception e) {
                log.debug("[ReleaseDoc] 跨仓库推断异常 {}: {}", other.getName(), e.getMessage());
            }
        }
        return risks.stream().distinct().limit(20).collect(Collectors.toList());
    }

    // ── Markdown 生成（四视角）────────────────────────────────────────────────

    private String buildMarkdown(List<RepoSelection> selections,
            List<ConfigChange> configs, List<SqlChange> sqls, List<ApiChange> apis,
            List<DependencyChange> deps, List<DeployChange> deploys,
            List<CommitInfo> commits, List<FileChange> others,
            List<CodeChangeAnalysis> codeChanges, List<CrossRepoRisk> crossRisks) {

        StringBuilder md = new StringBuilder();
        String now = java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 16);

        // 标题
        md.append("# 上线部署文档\n\n");
        for (RepoSelection sel : selections) {
            RepositoryEntity repo = repositoryRepo.findById(sel.repoId()).orElse(null);
            String name = repo != null ? repo.getName() : "repo-" + sel.repoId();
            md.append("**").append(now).append("** · ").append(name)
              .append(" `").append(sel.branch()).append("` → `").append(sel.baseBranch()).append("`\n\n");
        }

        long total = configs.size() + sqls.size() + apis.size() + deps.size()
                     + deploys.size() + codeChanges.size();
        if (total == 0) {
            md.append("> 本次分支与基线无业务变更。\n");
            return md.toString();
        }

        List<ConfigChange> prodCfg = configs.stream().filter(c -> !isDevConfigFile(c.file())).collect(Collectors.toList());
        List<ConfigChange> devCfg  = configs.stream().filter(c ->  isDevConfigFile(c.file())).collect(Collectors.toList());

        // 变更概述（一句话总结本次上线内容）
        String aiSummary = buildAiSummary(prodCfg, sqls, apis, codeChanges, deploys, crossRisks);
        md.append("## 变更概述\n\n");
        if (aiSummary != null && !aiSummary.isBlank()) {
            md.append(aiSummary.trim()).append("\n\n");
        } else {
            md.append(buildFallbackSummary(prodCfg, sqls, apis, deps, deploys)).append("\n\n");
        }
        md.append("---\n\n");

        // 一、部署操作：SQL / 配置 / 依赖 / 部署脚本
        appendDeploySteps(md, prodCfg, sqls, deps, deploys);

        // 二、外部依赖：DB / Redis / MQ / HTTP
        appendExternalDeps(md, codeChanges, sqls, configs);

        // 三、接口变更
        appendApiChanges(md, apis);

        // 附：删除的配置（参考）
        appendDeletedConfig(md, prodCfg);

        md.append("\n---\n*由 JavaCG2 自动生成*\n");
        return md.toString();
    }

    /** 无 AI 时的兜底概述 */
    private String buildFallbackSummary(List<ConfigChange> prodCfg, List<SqlChange> sqls,
            List<ApiChange> apis, List<DependencyChange> deps, List<DeployChange> deploys) {
        List<String> parts = new ArrayList<>();
        if (!apis.isEmpty())    parts.add("接口变更 " + apis.size() + " 项");
        if (!sqls.isEmpty())    parts.add("SQL 变更 " + sqls.size() + " 处");
        if (!prodCfg.isEmpty()) parts.add("生产配置变更 " + prodCfg.size() + " 项");
        if (!deps.isEmpty())    parts.add("依赖变更 " + deps.size() + " 项");
        if (!deploys.isEmpty()) parts.add("部署脚本变更 " + deploys.size() + " 项");
        return parts.isEmpty() ? "本次无关键部署项。" : "本次上线包含：" + String.join("、", parts) + "。";
    }

    /** 一、部署操作：按顺序执行的 SQL / 配置 / 依赖 / 部署脚本 */
    private void appendDeploySteps(StringBuilder md, List<ConfigChange> prodCfg,
            List<SqlChange> sqls, List<DependencyChange> deps, List<DeployChange> deploys) {

        md.append("## 一、部署操作\n\n");

        boolean hasDrop = sqls.stream().anyMatch(s ->
            s.detail().toUpperCase().contains("DROP") || s.detail().toUpperCase().contains("TRUNCATE"));
        boolean hasSensitive = prodCfg.stream().anyMatch(c -> {
            String k = c.key().toLowerCase();
            return k.contains("password") || k.contains("secret") || k.contains("token");
        });
        if (hasDrop || hasSensitive) {
            md.append("> ⚠️ ");
            if (hasDrop)      md.append("含不可逆 SQL（DROP/TRUNCATE），执行前务必备份相关表。 ");
            if (hasSensitive) md.append("含敏感配置（password/secret/token），请通过配置中心加密下发，禁止明文。");
            md.append("\n\n");
        }

        int step = 1;

        if (!sqls.isEmpty()) {
            md.append("### ").append(step++).append(". 执行 SQL\n\n");
            sqls.forEach(s -> md.append("`").append(s.file()).append("`\n\n```sql\n")
                .append(s.detail()).append("\n```\n\n"));
        }

        List<ConfigChange> toSet = prodCfg.stream()
            .filter(c -> !"删除".equals(c.changeType())).collect(Collectors.toList());
        if (!toSet.isEmpty()) {
            md.append("### ").append(step++).append(". 配置变更（新增/修改）\n\n");
            toSet.stream().collect(Collectors.groupingBy(ConfigChange::file, LinkedHashMap::new, Collectors.toList()))
                .forEach((file, items) -> {
                    md.append("`").append(file).append("`\n\n```yaml\n");
                    items.forEach(c -> md.append(c.key()).append(": ").append(truncate(c.value(), 200)).append("\n"));
                    md.append("```\n\n");
                });
        }

        if (!deps.isEmpty()) {
            md.append("### ").append(step++).append(". 依赖变更\n\n");
            deps.forEach(d -> md.append("- `").append(d.artifact()).append("` ")
                .append(d.fromVersion().isEmpty() ? "新增 `" + d.toVersion() + "`"
                    : "`" + d.fromVersion() + "` → `" + d.toVersion() + "`").append("\n"));
            md.append("\n");
        }

        if (!deploys.isEmpty()) {
            md.append("### ").append(step++).append(". 部署脚本变更\n\n");
            deploys.forEach(d -> md.append("- `").append(d.file()).append("` — ")
                .append(d.changeType()).append("（").append(d.detail()).append("）\n"));
            md.append("\n");
        }

        if (step == 1) md.append("> 本次无需数据库 / 配置 / 依赖 / 部署脚本操作。\n\n");
        md.append("---\n\n");
    }

    /** 二、外部依赖：DB / Redis / MQ / HTTP */
    private void appendExternalDeps(StringBuilder md, List<CodeChangeAnalysis> codeChanges,
            List<SqlChange> sqls, List<ConfigChange> configs) {

        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (CodeChangeAnalysis c : codeChanges) {
            for (String b : c.boundarySummaries()) {
                String type = b.contains("(") ? b.substring(0, b.indexOf('(')) : b;
                deps.computeIfAbsent(normalizeBoundary(type), k -> new LinkedHashSet<>())
                    .add(shortName(c.className()));
            }
        }
        if (!sqls.isEmpty()) deps.computeIfAbsent("数据库", k -> new LinkedHashSet<>()).add("SQL 脚本变更");
        configs.stream().filter(c -> c.key().toLowerCase().contains("redis")).findAny()
            .ifPresent(c -> deps.computeIfAbsent("Redis", k -> new LinkedHashSet<>()).add("配置变更"));
        configs.stream().filter(c -> { String k = c.key().toLowerCase();
                return k.contains("rocketmq") || k.contains("kafka") || k.contains("rabbit") || k.contains("mq."); })
            .findAny().ifPresent(c -> deps.computeIfAbsent("MQ", k -> new LinkedHashSet<>()).add("配置变更"));

        md.append("## 二、外部依赖\n\n");
        if (deps.isEmpty()) {
            md.append("> 本次变更未涉及数据库 / 缓存 / 消息队列 / 外部接口。\n\n---\n\n");
            return;
        }
        md.append("| 类型 | 涉及组件 |\n|------|----------|\n");
        deps.forEach((type, comps) -> md.append("| ").append(type).append(" | ")
            .append(comps.stream().limit(10).collect(Collectors.joining("、"))).append(" |\n"));
        md.append("\n---\n\n");
    }

    private String normalizeBoundary(String type) {
        String t = type.toUpperCase();
        if (t.contains("DB") || t.contains("SQL") || t.contains("JDBC") || t.contains("MYBATIS")) return "数据库";
        if (t.contains("CACHE") || t.contains("REDIS"))                                          return "Redis";
        if (t.contains("MQ") || t.contains("KAFKA") || t.contains("ROCKET") || t.contains("RABBIT")) return "MQ";
        if (t.contains("HTTP") || t.contains("FEIGN") || t.contains("REST"))                     return "HTTP";
        return type;
    }

    /** 三、接口变更 */
    private void appendApiChanges(StringBuilder md, List<ApiChange> apis) {
        md.append("## 三、接口变更\n\n");
        if (apis.isEmpty()) {
            md.append("> 本次无接口新增 / 修改。\n\n");
            return;
        }
        apis.stream().collect(Collectors.groupingBy(ApiChange::changeType, LinkedHashMap::new, Collectors.toList()))
            .forEach((type, list) -> {
                md.append("**").append(type).append("**\n\n");
                list.forEach(a -> md.append("- `").append(a.method()).append(" ").append(a.path()).append("`\n"));
                md.append("\n");
            });
    }

    /** 附：删除的配置（折叠参考，上线时确认是否需要从环境移除） */
    private void appendDeletedConfig(StringBuilder md, List<ConfigChange> prodCfg) {
        List<ConfigChange> deleted = prodCfg.stream()
            .filter(c -> "删除".equals(c.changeType())).collect(Collectors.toList());
        if (deleted.isEmpty()) return;
        md.append("<details>\n<summary>删除的配置（参考，确认是否需从环境移除）</summary>\n\n");
        deleted.stream().collect(Collectors.groupingBy(ConfigChange::file, LinkedHashMap::new, Collectors.toList()))
            .forEach((file, items) -> {
                md.append("`").append(file).append("`\n\n```yaml\n");
                items.forEach(c -> md.append(c.key()).append(": ").append(truncate(c.value(), 200)).append("\n"));
                md.append("```\n\n");
            });
        md.append("</details>\n\n");
    }

    // ── AI 整体摘要 ───────────────────────────────────────────────────────────

    private String buildAiSummary(List<ConfigChange> prodCfg, List<SqlChange> sqls,
            List<ApiChange> apis, List<CodeChangeAnalysis> codeChanges,
            List<DeployChange> deploys, List<CrossRepoRisk> crossRisks) {
        if (!claudeClient.isConfigured()) return null;
        try {
            StringBuilder ctx = new StringBuilder("请用2-3句话总结本次版本发布的核心变更和主要风险（面向技术负责人，不超过120字）：\n");
            if (!apis.isEmpty()) ctx.append("接口变更 ").append(apis.size()).append(" 个；");
            if (!sqls.isEmpty()) ctx.append("SQL变更 ").append(sqls.size()).append(" 处；");
            if (!prodCfg.isEmpty()) ctx.append("生产配置变更 ").append(prodCfg.size()).append(" 项；");
            if (!deploys.isEmpty()) ctx.append("部署配置变更；");
            long ctrlCount = codeChanges.stream().filter(c -> "CONTROLLER".equals(c.category())).count();
            long svcCount  = codeChanges.stream().filter(c -> "SERVICE".equals(c.category())).count();
            long mqCount   = codeChanges.stream().filter(c -> "MQ".equals(c.category())).count();
            if (ctrlCount > 0) ctx.append("Controller变动 ").append(ctrlCount).append(" 个；");
            if (svcCount  > 0) ctx.append("Service变动 ").append(svcCount).append(" 个；");
            if (mqCount   > 0) ctx.append("MQ监听变动 ").append(mqCount).append(" 个；");
            if (!crossRisks.isEmpty()) ctx.append("存在 ").append(crossRisks.size()).append(" 个跨仓库关联风险；");
            return claudeClient.chat(promptService.get("release.summary"),
                List.of(Map.of("role", "user", "content", ctx.toString())));
        } catch (Exception e) {
            log.warn("[ReleaseDoc] AI摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /** 取类名最后一段（短名） */
    private String shortName(String className) {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    // ── Git 操作 ──────────────────────────────────────────────────────────────

    private boolean branchExists(String localPath, String branch) {
        String out = execGit(localPath, "git", "branch", "-a");
        return out.contains(branch) || out.contains("origin/" + branch);
    }

    private List<String[]> getChangedFiles(String localPath, String baseBranch, String branch) {
        String out = execGit(localPath, "git", "diff", "--name-status",
            "origin/" + baseBranch + "...origin/" + branch);
        if (out.isBlank()) return List.of();
        return out.lines().filter(l -> l.length() > 2).map(line -> new String[]{
            line.substring(0, 1), line.substring(1).trim()
        }).collect(Collectors.toList());
    }

    private String getFileDiff(String localPath, String filePath, String baseBranch, String branch) {
        return execGit(localPath, "git", "diff",
            "origin/" + baseBranch + "...origin/" + branch, "--", filePath);
    }

    private int[] getDiffStats(String localPath, String filePath, String baseBranch, String branch) {
        String out = execGit(localPath, "git", "diff", "--numstat",
            "origin/" + baseBranch + "...origin/" + branch, "--", filePath);
        if (out.isBlank()) return new int[]{0, 0};
        String[] parts = out.trim().split("\\s+");
        try { return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])}; }
        catch (Exception e) { return new int[]{0, 0}; }
    }

    private String execGit(String workDir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return ""; }
            return new String(p.getInputStream().readAllBytes());
        } catch (Exception e) {
            log.error("[ReleaseDoc] git命令失败: {} in {}", String.join(" ", cmd), workDir, e);
            return "";
        }
    }

    // ── 文件分类 ──────────────────────────────────────────────────────────────

    private boolean isConfigFile(String path) {
        if (path.contains(".claude/") || path.contains(".uai/") || path.contains(".harness/")
            || path.endsWith(".md") || path.endsWith(".txt")) return false;
        return (path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties"))
            && (path.contains("src/main/resources") || path.contains("config/")
                || path.startsWith("application") || path.startsWith("bootstrap")
                || path.contains("nacos") || path.contains("apollo"));
    }

    private boolean isDevConfigFile(String path) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return fileName.contains("-local.") || fileName.contains("-test.") || fileName.contains("-dev.")
            || fileName.contains("-sit.") || fileName.contains("-uat.");
    }

    private boolean isSqlFile(String path) {
        return !path.contains(".claude/") && (path.endsWith(".sql")
            || path.contains("db/migration") || path.contains("sql/"));
    }

    private boolean isDependencyFile(String path) {
        return path.endsWith("pom.xml") || path.endsWith("build.gradle")
            || path.endsWith("build.gradle.kts");
    }

    private boolean isDeployFile(String path) {
        return path.contains("Dockerfile") || path.contains("docker-compose")
            || path.contains(".gitlab-ci") || path.contains("k8s") || path.contains("helm");
    }

    private boolean isApiFile(String path) {
        return path.endsWith(".java") && !path.contains("test/")
            && (path.contains("Controller") || path.contains("controller/"));
    }

    // ── Diff 解析器 ───────────────────────────────────────────────────────────

    private List<ConfigChange> parseConfigDiff(String localPath, String repoName,
            String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        Map<String, String> removed = new LinkedHashMap<>(), added = new LinkedHashMap<>();
        for (String line : diff.split("\n")) {
            boolean isMinus = line.startsWith("-") && !line.startsWith("---");
            boolean isPlus  = line.startsWith("+") && !line.startsWith("+++");
            if (!isMinus && !isPlus) continue;
            String content = line.substring(1).trim();
            if (content.isBlank() || content.startsWith("#")) continue;
            if (content.matches("^[\\w.\\-]+:\\s*$")) continue;
            String key = content.split("[=:]")[0].trim();
            String value = content.contains("=") ? content.substring(content.indexOf('=') + 1).trim()
                : content.contains(":") ? content.substring(content.indexOf(':') + 1).trim() : "";
            if (value.isBlank()) continue;
            if (isMinus) removed.put(key, value); else added.put(key, value);
        }
        List<ConfigChange> changes = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(removed.keySet()); allKeys.addAll(added.keySet());
        for (String key : allKeys) {
            boolean inR = removed.containsKey(key), inA = added.containsKey(key);
            if (inR && inA) {
                if (!removed.get(key).equals(added.get(key)))
                    changes.add(new ConfigChange(repoName, filePath, "修改", key, added.get(key)));
            } else if (inA) changes.add(new ConfigChange(repoName, filePath, "新增", key, added.get(key)));
            else             changes.add(new ConfigChange(repoName, filePath, "删除", key, removed.get(key)));
        }
        return changes;
    }

    private List<SqlChange> parseSqlDiff(String localPath, String repoName,
            String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        if (diff.isBlank()) return List.of();
        List<String> addedLines = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            String content = line.substring(1).trim();
            if (!content.isBlank() && !content.startsWith("--") && !content.startsWith("#"))
                addedLines.add(content);
        }
        if (addedLines.isEmpty()) return List.of();
        String sqlContent = String.join("\n", addedLines.size() > 50 ? addedLines.subList(0, 50) : addedLines);
        if (addedLines.size() > 50) sqlContent += "\n... (共 " + addedLines.size() + " 行)";
        return List.of(new SqlChange(repoName, filePath, "SQL", filePath, sqlContent));
    }

    private List<DependencyChange> parseDependencyDiff(String localPath, String repoName,
            String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<DependencyChange> changes = new ArrayList<>();
        Pattern ap = Pattern.compile("<artifactId>(.*?)</artifactId>");
        Pattern vp = Pattern.compile("<version>(.*?)</version>");
        String[] lines = diff.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("+") || lines[i].startsWith("+++")) continue;
            Matcher am = ap.matcher(lines[i]);
            if (am.find()) {
                String artifact = am.group(1);
                String version = "";
                for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                    Matcher vm = vp.matcher(lines[j]);
                    if (vm.find()) { version = vm.group(1); break; }
                }
                changes.add(new DependencyChange(repoName, filePath, artifact, "", version));
            }
        }
        return changes;
    }

    private List<ApiChange> parseApiDiff(String localPath, String repoName,
            String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<ApiChange> changes = new ArrayList<>();
        Pattern p = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(.*?(?:value\\s*=\\s*)?\"([^\"]+)\"");
        for (String line : diff.split("\n")) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            Matcher m = p.matcher(line);
            if (m.find()) {
                String method = m.group(1).toUpperCase().replace("REQUEST", "ALL");
                changes.add(new ApiChange(repoName, filePath, method, m.group(2), "新增"));
            }
        }
        return changes;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private String summarizeDiff(String diff) {
        long a = diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        long r = diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
        return "+" + a + " / -" + r + " 行";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
