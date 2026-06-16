package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.exception.GitOperationException;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.RepositoryManager;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class RepositoryManagerImpl implements RepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryManagerImpl.class);

    private final RepositoryRepo repositoryRepo;
    private final com.adrninistrator.javacg2.platform.repository.SystemConfigRepo systemConfigRepo;

    @Value("${platform.repo-base-dir:./data/repos}")
    private String repoBaseDir;

    public RepositoryManagerImpl(RepositoryRepo repositoryRepo,
                                  com.adrninistrator.javacg2.platform.repository.SystemConfigRepo systemConfigRepo) {
        this.repositoryRepo = repositoryRepo;
        this.systemConfigRepo = systemConfigRepo;
    }

    /** 获取有效 token：用户传入 > 全局配置 */
    private String resolveToken(String userToken) {
        if (userToken != null && !userToken.isBlank()) return userToken;
        return systemConfigRepo.findByConfigKey("git.default.token")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    /** 获取有效 repoType：用户传入 > 全局配置 > 默认 GITLAB */
    private String resolveRepoType(String userRepoType) {
        if (userRepoType != null && !userRepoType.isBlank()) return userRepoType;
        return systemConfigRepo.findByConfigKey("git.default.repo.type")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("GITLAB");
    }

    @Override
    public List<String> listRemoteBranches(String gitUrl, String token, String repoType) {
        String effectiveToken = resolveToken(token);
        String effectiveRepoType = resolveRepoType(repoType);
        try {
            var lsCmd = Git.lsRemoteRepository().setRemote(gitUrl).setHeads(true);
            if (effectiveToken != null) {
                lsCmd.setCredentialsProvider(buildCredentials(effectiveToken, effectiveRepoType));
            }
            var refs = lsCmd.call();
            List<String> branches = new ArrayList<>();
            for (var ref : refs) {
                String name = ref.getName();
                // refs/heads/main -> main
                if (name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                }
            }
            branches.sort(String::compareTo);
            logger.info("获取远程分支: {} -> {} 个分支", gitUrl, branches.size());
            return branches;
        } catch (Exception e) {
            logger.error("获取远程分支失败: {}", gitUrl, e);
            throw new GitOperationException("BRANCH_LIST_FAILED",
                    "获取分支列表失败: " + e.getMessage(),
                    "请检查仓库 URL 和 Token 是否正确");
        }
    }

    @Override
    public RepoResult cloneRepository(String gitUrl, String token, String repoType, String branch) {
        String effectiveToken = resolveToken(token);
        String effectiveRepoType = resolveRepoType(repoType);

        String repoName = extractRepoName(gitUrl);
        String localPath = new File(repoBaseDir, repoName + "_" + System.currentTimeMillis()).getAbsolutePath();

        RepositoryEntity entity = new RepositoryEntity();
        entity.setName(repoName);
        entity.setGitUrl(gitUrl);
        entity.setTokenEncrypted(effectiveToken);
        entity.setRepoType(effectiveRepoType);
        entity.setBranch(branch != null ? branch : "main");
        entity.setLocalPath(localPath);
        entity.setStatus("CLONING");
        entity.setCreatedAt(LocalDateTime.now());
        entity = repositoryRepo.save(entity);

        try {
            CloneCommand cloneCmd = Git.cloneRepository()
                    .setURI(gitUrl)
                    .setDirectory(new File(localPath))
                    .setBranch(branch != null ? branch : "main");

            if (effectiveToken != null) {
                cloneCmd.setCredentialsProvider(buildCredentials(effectiveToken, effectiveRepoType));
            }

            try (Git git = cloneCmd.call()) {
                String commitHash = git.getRepository().resolve("HEAD").getName();
                entity.setLastCommitHash(commitHash);
                entity.setLastSyncTime(LocalDateTime.now());
                entity.setStatus("READY");
                repositoryRepo.save(entity);
                logger.info("仓库克隆成功: {} -> {}", gitUrl, localPath);
                return new RepoResult(true, "克隆成功", entity.getId());
            }
        } catch (Exception e) {
            entity.setStatus("ERROR");
            repositoryRepo.save(entity);
            logger.error("仓库克隆失败: {}", gitUrl, e);
            throw new GitOperationException("CLONE_FAILED", "克隆失败: " + e.getMessage(),
                    "请检查仓库 URL 和 Token 是否正确");
        }
    }

    @Override
    public RepoResult pullRepository(Long repoId) {
        RepositoryEntity entity = findRepo(repoId);
        try (Git git = Git.open(new File(entity.getLocalPath()))) {
            org.eclipse.jgit.api.PullCommand pullCmd = git.pull();

            String token = entity.getTokenEncrypted();
            if (token != null && !token.isBlank()) {
                pullCmd.setCredentialsProvider(buildCredentials(token, entity.getRepoType()));
            }

            PullResult result = pullCmd.call();
            if (result.isSuccessful()) {
                String commitHash = git.getRepository().resolve("HEAD").getName();
                entity.setLastCommitHash(commitHash);
                entity.setLastSyncTime(LocalDateTime.now());
                entity.setStatus("READY");
                repositoryRepo.save(entity);
                logger.info("仓库拉取成功: {}", entity.getName());
                return new RepoResult(true, "拉取成功", repoId);
            } else {
                throw new GitOperationException("PULL_FAILED", "拉取失败: merge 冲突",
                        "请手动解决冲突后重试");
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("仓库拉取失败: {}", entity.getName(), e);
            throw new GitOperationException("PULL_FAILED", "拉取失败: " + e.getMessage(),
                    "请检查网络连接和认证信息");
        }
    }

    @Override
    public List<String> detectChanges(Long repoId) {
        RepositoryEntity entity = findRepo(repoId);
        String lastCommit = entity.getLastCommitHash();
        if (lastCommit == null) {
            return List.of(); // 首次分析，无增量
        }

        try (Git git = Git.open(new File(entity.getLocalPath()))) {
            Repository repo = git.getRepository();
            ObjectId headId = repo.resolve("HEAD");
            ObjectId oldId = repo.resolve(lastCommit);

            if (headId.equals(oldId)) {
                return List.of(); // 无变更
            }

            try (ObjectReader reader = repo.newObjectReader();
                 RevWalk walk = new RevWalk(repo)) {

                RevCommit headCommit = walk.parseCommit(headId);
                RevCommit oldCommit = walk.parseCommit(oldId);

                CanonicalTreeParser headTree = new CanonicalTreeParser();
                headTree.reset(reader, headCommit.getTree());

                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, oldCommit.getTree());

                List<DiffEntry> diffs = git.diff()
                        .setNewTree(headTree)
                        .setOldTree(oldTree)
                        .call();

                List<String> changedFiles = new ArrayList<>();
                for (DiffEntry diff : diffs) {
                    String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? diff.getOldPath() : diff.getNewPath();
                    if (path.endsWith(".java") || path.endsWith(".class")) {
                        changedFiles.add(path);
                    }
                }
                return changedFiles;
            }
        } catch (Exception e) {
            logger.error("变更检测失败: {}", entity.getName(), e);
            return List.of();
        }
    }

    @Override
    public RepoResult triggerBuild(Long repoId) {
        RepositoryEntity entity = findRepo(repoId);
        entity.setStatus("BUILDING");
        repositoryRepo.save(entity);

        try {
            File repoDir = new File(entity.getLocalPath());
            // 检查是否有 gradlew
            File gradlew = new File(repoDir, "gradlew");
            File pomXml = new File(repoDir, "pom.xml");

            ProcessBuilder pb;
            if (gradlew.exists()) {
                pb = new ProcessBuilder("./gradlew", "build", "-x", "test");
            } else if (pomXml.exists()) {
                pb = new ProcessBuilder("mvn", "package", "-DskipTests");
            } else {
                entity.setStatus("READY");
                repositoryRepo.save(entity);
                return new RepoResult(true, "无构建文件，跳过编译", repoId);
            }

            pb.directory(repoDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                entity.setStatus("READY");
                repositoryRepo.save(entity);
                logger.info("编译成功: {}", entity.getName());
                return new RepoResult(true, "编译成功", repoId);
            } else {
                entity.setStatus("ERROR");
                repositoryRepo.save(entity);
                String output = new String(process.getInputStream().readAllBytes());
                logger.error("编译失败: {}, output: {}", entity.getName(), output);
                throw new GitOperationException("BUILD_FAILED", "编译失败 (exit code: " + exitCode + ")",
                        "请检查项目是否能正常编译");
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            entity.setStatus("ERROR");
            repositoryRepo.save(entity);
            logger.error("编译触发失败: {}", entity.getName(), e);
            throw new GitOperationException("BUILD_FAILED", "编译触发失败: " + e.getMessage(),
                    "请检查构建环境");
        }
    }

    @Override
    public void deleteRepository(Long repoId) {
        RepositoryEntity entity = findRepo(repoId);
        // 删除本地文件
        File localDir = new File(entity.getLocalPath());
        if (localDir.exists()) {
            deleteDirectory(localDir);
        }
        repositoryRepo.delete(entity);
        logger.info("仓库已删除: {}", entity.getName());
    }

    private RepositoryEntity findRepo(Long repoId) {
        return repositoryRepo.findById(repoId)
                .orElseThrow(() -> new GitOperationException("NOT_FOUND", "仓库不存在: " + repoId,
                        "请检查仓库 ID", 404));
    }

    private CredentialsProvider buildCredentials(String token, String repoType) {
        if ("GITLAB".equalsIgnoreCase(repoType)) {
            // GitLab: 用 oauth2 作为用户名
            return new UsernamePasswordCredentialsProvider("oauth2", token);
        }
        // GitHub 和其他: 用 token 作为用户名和密码都行
        return new UsernamePasswordCredentialsProvider(token, token);
    }

    private String extractRepoName(String gitUrl) {
        String name = gitUrl;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name.isEmpty() ? "unknown" : name;
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
