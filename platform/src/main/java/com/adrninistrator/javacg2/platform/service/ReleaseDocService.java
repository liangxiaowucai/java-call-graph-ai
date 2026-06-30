package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
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
 * 从 git diff 中提取变更，分类整理为结构化 Markdown 文档。
 * 不依赖 AI，纯事实提取，不会幻读。
 */
@Service
public class ReleaseDocService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseDocService.class);

    private final RepositoryRepo repositoryRepo;

    public ReleaseDocService(RepositoryRepo repositoryRepo) {
        this.repositoryRepo = repositoryRepo;
    }

    // ── 请求/响应 DTO ──────────────────────────────────────────────────────────

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

    // ── 主入口 ────────────────────────────────────────────────────────────────

    public ReleaseDoc generate(List<RepoSelection> selections) {
        List<ConfigChange> configChanges = new ArrayList<>();
        List<SqlChange> sqlChanges = new ArrayList<>();
        List<ApiChange> apiChanges = new ArrayList<>();
        List<DependencyChange> dependencyChanges = new ArrayList<>();
        List<DeployChange> deployChanges = new ArrayList<>();
        List<CommitInfo> commits = new ArrayList<>();
        List<FileChange> otherChanges = new ArrayList<>();

        for (RepoSelection sel : selections) {
            RepositoryEntity repo = repositoryRepo.findById(sel.repoId()).orElse(null);
            if (repo == null) continue;

            String repoName = repo.getName();
            String localPath = repo.getLocalPath();
            String branch = sel.branch();
            String baseBranch = sel.baseBranch() != null ? sel.baseBranch() : "master";

            // 确保分支存在
            if (!branchExists(localPath, branch)) {
                log.warn("[ReleaseDoc] 分支不存在: {} in {}", branch, repoName);
                continue;
            }

            // 1. git log
            List<CommitInfo> repoCommits = getCommits(localPath, repoName, baseBranch, branch);
            commits.addAll(repoCommits);

            // 2. git diff --name-status (文件变更列表)
            List<String[]> changedFiles = getChangedFiles(localPath, baseBranch, branch);

            // 3. 分类解析
            for (String[] fileInfo : changedFiles) {
                String status = fileInfo[0]; // A/M/D
                String filePath = fileInfo[1];

                String statusLabel = "A".equals(status) ? "新增" : "D".equals(status) ? "删除" : "修改";

                // 配置文件
                if (isConfigFile(filePath)) {
                    List<ConfigChange> changes = parseConfigDiff(localPath, repoName, filePath, baseBranch, branch);
                    configChanges.addAll(changes);
                }
                // SQL 文件
                else if (isSqlFile(filePath)) {
                    List<SqlChange> changes = parseSqlDiff(localPath, repoName, filePath, baseBranch, branch);
                    sqlChanges.addAll(changes);
                }
                // 依赖文件
                else if (isDependencyFile(filePath)) {
                    List<DependencyChange> changes = parseDependencyDiff(localPath, repoName, filePath, baseBranch, branch);
                    dependencyChanges.addAll(changes);
                }
                // 部署文件
                else if (isDeployFile(filePath)) {
                    String diff = getFileDiff(localPath, filePath, baseBranch, branch);
                    deployChanges.add(new DeployChange(repoName, filePath, statusLabel, summarizeDiff(diff)));
                }
                // Controller/接口文件
                else if (isApiFile(filePath)) {
                    List<ApiChange> changes = parseApiDiff(localPath, repoName, filePath, baseBranch, branch);
                    apiChanges.addAll(changes);
                }
                // 其他 Java 文件
                else if (filePath.endsWith(".java")) {
                    int[] stats = getDiffStats(localPath, filePath, baseBranch, branch);
                    otherChanges.add(new FileChange(repoName, filePath, statusLabel, stats[0], stats[1]));
                }
            }
        }

        // 生成 Markdown
        String markdown = buildMarkdown(selections, configChanges, sqlChanges, apiChanges,
                dependencyChanges, deployChanges, commits, otherChanges);

        return new ReleaseDoc(markdown, configChanges, sqlChanges, apiChanges,
                dependencyChanges, deployChanges, commits, otherChanges);
    }

    // ── Git 操作 ──────────────────────────────────────────────────────────────

    private boolean branchExists(String localPath, String branch) {
        String out = execGit(localPath, "git", "branch", "-a");
        return out.contains(branch);
    }

    private List<CommitInfo> getCommits(String localPath, String repoName, String baseBranch, String branch) {
        String range = baseBranch + ".." + branch;
        String out = execGit(localPath, "git", "log", range, "--pretty=format:%H|%s|%an|%ad", "--date=short");
        if (out.isBlank()) return List.of();
        return out.lines()
            .filter(l -> l.contains("|"))
            .map(line -> {
                String[] parts = line.split("\\|", 4);
                return new CommitInfo(repoName,
                    parts[0].substring(0, Math.min(8, parts[0].length())),
                    parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "",
                    parts.length > 3 ? parts[3] : "");
            })
            .collect(Collectors.toList());
    }

    private List<String[]> getChangedFiles(String localPath, String baseBranch, String branch) {
        String out = execGit(localPath, "git", "diff", "--name-status", baseBranch + "..." + branch);
        if (out.isBlank()) return List.of();
        return out.lines()
            .filter(l -> l.length() > 2)
            .map(line -> {
                String status = line.substring(0, 1);
                String file = line.substring(1).trim();
                return new String[]{status, file};
            })
            .collect(Collectors.toList());
    }

    private String getFileDiff(String localPath, String filePath, String baseBranch, String branch) {
        return execGit(localPath, "git", "diff", baseBranch + "..." + branch, "--", filePath);
    }

    private int[] getDiffStats(String localPath, String filePath, String baseBranch, String branch) {
        String out = execGit(localPath, "git", "diff", "--numstat", baseBranch + "..." + branch, "--", filePath);
        if (out.isBlank()) return new int[]{0, 0};
        String[] parts = out.trim().split("\\s+");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) { return new int[]{0, 0}; }
    }

    // ── 文件分类 ──────────────────────────────────────────────────────────────

    private boolean isConfigFile(String path) {
        return path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties")
            || path.contains("application") || path.contains("bootstrap");
    }

    private boolean isSqlFile(String path) {
        return path.endsWith(".sql") || path.contains("db/migration") || path.contains("sql/");
    }

    private boolean isDependencyFile(String path) {
        return path.endsWith("pom.xml") || path.endsWith("build.gradle") || path.endsWith("build.gradle.kts");
    }

    private boolean isDeployFile(String path) {
        return path.contains("Dockerfile") || path.contains("docker-compose")
            || path.endsWith(".sh") || path.contains("k8s") || path.contains("helm");
    }

    private boolean isApiFile(String path) {
        return path.contains("Controller") || path.contains("controller/");
    }

    // ── Diff 解析器 ───────────────────────────────────────────────────────────

    private List<ConfigChange> parseConfigDiff(String localPath, String repoName, String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<ConfigChange> changes = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String content = line.substring(1).trim();
                if (content.isBlank() || content.startsWith("#")) continue;
                // key: value 或 key=value
                String key = content.split("[=:]")[0].trim();
                String value = content.contains("=") ? content.substring(content.indexOf('=') + 1).trim()
                    : content.contains(":") ? content.substring(content.indexOf(':') + 1).trim() : "";
                changes.add(new ConfigChange(repoName, filePath, "新增", key, value));
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                String content = line.substring(1).trim();
                if (content.isBlank() || content.startsWith("#")) continue;
                String key = content.split("[=:]")[0].trim();
                changes.add(new ConfigChange(repoName, filePath, "删除", key, ""));
            }
        }
        return changes;
    }

    private List<SqlChange> parseSqlDiff(String localPath, String repoName, String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<SqlChange> changes = new ArrayList<>();
        Pattern tablePattern = Pattern.compile("(?i)(CREATE|ALTER|DROP)\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?`?(\\w+)`?");
        for (String line : diff.split("\n")) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            Matcher m = tablePattern.matcher(line);
            if (m.find()) {
                changes.add(new SqlChange(repoName, filePath, m.group(1).toUpperCase(), m.group(2), line.substring(1).trim()));
            }
        }
        // 如果没匹配到具体语句，把文件作为整体记录
        if (changes.isEmpty() && !diff.isBlank()) {
            changes.add(new SqlChange(repoName, filePath, "SQL变更", filePath, "详见文件内容"));
        }
        return changes;
    }

    private List<DependencyChange> parseDependencyDiff(String localPath, String repoName, String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<DependencyChange> changes = new ArrayList<>();
        // 简单提取 <artifactId> 和 <version> 变更
        Pattern artifactPattern = Pattern.compile("<artifactId>(.*?)</artifactId>");
        Pattern versionPattern = Pattern.compile("<version>(.*?)</version>");
        String[] lines = diff.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("+") && !line.startsWith("+++")) {
                Matcher am = artifactPattern.matcher(line);
                if (am.find()) {
                    String artifact = am.group(1);
                    // Look for version in next few lines
                    String version = "";
                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                        Matcher vm = versionPattern.matcher(lines[j]);
                        if (vm.find()) { version = vm.group(1); break; }
                    }
                    changes.add(new DependencyChange(repoName, filePath, artifact, "", version));
                }
            }
        }
        return changes;
    }

    private List<ApiChange> parseApiDiff(String localPath, String repoName, String filePath, String baseBranch, String branch) {
        String diff = getFileDiff(localPath, filePath, baseBranch, branch);
        List<ApiChange> changes = new ArrayList<>();
        Pattern mappingPattern = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(.*?(?:value\\s*=\\s*)?\"([^\"]+)\"");
        for (String line : diff.split("\n")) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            Matcher m = mappingPattern.matcher(line);
            if (m.find()) {
                String method = m.group(1).toUpperCase().replace("REQUEST", "ALL");
                String path = m.group(2);
                changes.add(new ApiChange(repoName, filePath, method, path, "新增"));
            }
        }
        return changes;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private String summarizeDiff(String diff) {
        long added = diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        long removed = diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
        return "+" + added + " / -" + removed + " 行";
    }

    private String execGit(String workDir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return output;
        } catch (Exception e) {
            log.error("[ReleaseDoc] git 命令执行失败: {} in {}", String.join(" ", cmd), workDir, e);
            return "";
        }
    }

    // ── Markdown 生成 ─────────────────────────────────────────────────────────

    private String buildMarkdown(List<RepoSelection> selections,
            List<ConfigChange> configs, List<SqlChange> sqls, List<ApiChange> apis,
            List<DependencyChange> deps, List<DeployChange> deploys,
            List<CommitInfo> commits, List<FileChange> others) {

        StringBuilder md = new StringBuilder();
        md.append("# 上线文档\n\n");
        md.append("**生成时间**: ").append(java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19)).append("\n\n");

        // 仓库概览表
        md.append("## 概览\n\n");
        md.append("| 仓库 | 功能分支 | 对比基线 | 提交数 | 变更文件 |\n");
        md.append("|------|----------|----------|--------|----------|\n");
        for (RepoSelection sel : selections) {
            RepositoryEntity repo = repositoryRepo.findById(sel.repoId()).orElse(null);
            String name = repo != null ? repo.getName() : "repo-" + sel.repoId();
            long commitCount = commits.stream().filter(c -> c.repo().equals(name)).count();
            long fileCount = others.stream().filter(f -> f.repo().equals(name)).count();
            md.append("| **").append(name).append("** | `").append(sel.branch()).append("` | `")
              .append(sel.baseBranch()).append("` | ").append(commitCount).append(" | ").append(fileCount).append(" |\n");
        }
        md.append("\n---\n\n");

        // 1. 配置变更
        if (!configs.isEmpty()) {
            md.append("## 1. ⚙️ 配置变更\n\n");
            md.append("> 以下配置项在分支中有新增或修改，上线前请确认目标环境已同步。\n\n");
            md.append("| 仓库 | 文件路径 | 类型 | 配置项 | 值 |\n");
            md.append("|------|----------|------|--------|----|\n");
            for (ConfigChange c : configs) {
                md.append("| ").append(c.repo()).append(" | `").append(c.file())
                  .append("` | ").append(c.changeType()).append(" | `").append(escape(c.key()))
                  .append("` | `").append(escape(truncate(c.value(), 60))).append("` |\n");
            }
            md.append("\n");
        }

        // 2. SQL 变更
        if (!sqls.isEmpty()) {
            md.append("## 2. 🗄️ SQL/数据库变更\n\n");
            md.append("> 上线前请在目标数据库执行以下 SQL 脚本。\n\n");
            md.append("| 仓库 | 文件路径 | 操作 | 表名 | SQL 摘要 |\n");
            md.append("|------|----------|------|------|----------|\n");
            for (SqlChange s : sqls) {
                md.append("| ").append(s.repo()).append(" | `").append(s.file())
                  .append("` | **").append(s.operation()).append("** | `").append(s.tableName())
                  .append("` | ").append(escape(truncate(s.detail(), 80))).append(" |\n");
            }
            md.append("\n");
        }

        // 3. 接口变更
        if (!apis.isEmpty()) {
            md.append("## 3. 🌐 接口变更（Controller）\n\n");
            md.append("> 新增或修改的 HTTP 接口，上线后需通知前端/调用方。\n\n");
            md.append("| 仓库 | HTTP 方法 | 路径 | 变更类型 | 文件路径 |\n");
            md.append("|------|-----------|------|----------|----------|\n");
            for (ApiChange a : apis) {
                md.append("| ").append(a.repo()).append(" | **").append(a.method())
                  .append("** | `").append(a.path()).append("` | ").append(a.changeType())
                  .append(" | `").append(a.file()).append("` |\n");
            }
            md.append("\n");
        }

        // 4. 依赖变更
        if (!deps.isEmpty()) {
            md.append("## 4. 📦 依赖变更\n\n");
            md.append("> 新增或升级的依赖，请确认兼容性。\n\n");
            md.append("| 仓库 | 依赖 Artifact | 版本 | 文件路径 |\n");
            md.append("|------|---------------|------|----------|\n");
            for (DependencyChange d : deps) {
                String ver = d.fromVersion().isEmpty() ? "新增 " + d.toVersion() : d.fromVersion() + " → " + d.toVersion();
                md.append("| ").append(d.repo()).append(" | `").append(d.artifact())
                  .append("` | ").append(ver).append(" | `").append(d.file()).append("` |\n");
            }
            md.append("\n");
        }

        // 5. 部署变更
        if (!deploys.isEmpty()) {
            md.append("## 5. 🐳 部署/环境变更\n\n");
            md.append("> Dockerfile、docker-compose、脚本等部署相关变更。\n\n");
            md.append("| 仓库 | 文件路径 | 变更类型 | 变更量 |\n");
            md.append("|------|----------|----------|--------|\n");
            for (DeployChange d : deploys) {
                md.append("| ").append(d.repo()).append(" | `").append(d.file())
                  .append("` | ").append(d.changeType()).append(" | ").append(d.detail()).append(" |\n");
            }
            md.append("\n");
        }

        // 6. 提交记录（按仓库分组，完整 hash 可回溯）
        if (!commits.isEmpty()) {
            md.append("## 6. 📝 Git 提交记录\n\n");
            String currentRepo = "";
            for (CommitInfo c : commits) {
                if (!c.repo().equals(currentRepo)) {
                    currentRepo = c.repo();
                    long count = commits.stream().filter(x -> x.repo().equals(currentRepo)).count();
                    md.append("### ").append(currentRepo).append(" (").append(count).append(" commits)\n\n");
                }
                md.append("- `").append(c.hash()).append("` ").append(escape(c.message()))
                  .append(" — *").append(c.author()).append("* ").append(c.date()).append("\n");
            }
            md.append("\n");
        }

        // 7. 其他代码变更文件清单
        if (!others.isEmpty()) {
            md.append("## 7. 📄 代码变更文件清单\n\n");
            md.append("| 仓库 | 文件路径 | 状态 | 增/删行 |\n");
            md.append("|------|----------|------|--------|\n");
            for (FileChange f : others.stream().limit(100).collect(Collectors.toList())) {
                md.append("| ").append(f.repo()).append(" | `").append(f.file())
                  .append("` | ").append(f.status()).append(" | +").append(f.additions())
                  .append(" / -").append(f.deletions()).append(" |\n");
            }
            if (others.size() > 100) {
                md.append("| | *... 共 ").append(others.size()).append(" 个文件* | | |\n");
            }
            md.append("\n");
        }

        // 尾部
        md.append("---\n\n");
        md.append("*本文档由 JavaCG2 自动从 git diff 生成，所有内容均来自版本控制系统的实际变更记录，可通过 commit hash 和文件路径完整回溯。*\n");

        return md.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String shortPath(String path) {
        if (path == null) return "";
        // 取最后两段路径
        String[] parts = path.split("/");
        if (parts.length <= 2) return path;
        return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
