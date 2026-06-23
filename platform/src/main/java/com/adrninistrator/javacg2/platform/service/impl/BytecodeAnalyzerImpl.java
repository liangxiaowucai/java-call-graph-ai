package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.conf.JavaCG2ConfigureWrapper;
import com.adrninistrator.javacg2.conf.enums.JavaCG2ConfigKeyEnum;
import com.adrninistrator.javacg2.conf.enums.JavaCG2OtherConfigFileUseListEnum;
import com.adrninistrator.javacg2.el.enums.JavaCG2ElConfigEnum;
import com.adrninistrator.javacg2.entry.JavaCG2Entry;
import com.adrninistrator.javacg2.platform.entity.*;
import com.adrninistrator.javacg2.platform.exception.AnalysisException;
import com.adrninistrator.javacg2.platform.repository.*;
import com.adrninistrator.javacg2.platform.service.BytecodeAnalyzer;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.BuildLogService;
import com.adrninistrator.javacg2.platform.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BytecodeAnalyzerImpl implements BytecodeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(BytecodeAnalyzerImpl.class);

    // 匹配双引号内包含中文的字符串
    private static final java.util.regex.Pattern CHINESE_STRING_PATTERN =
            java.util.regex.Pattern.compile("\"([^\"]*[\\u4e00-\\u9fff][^\"]*)\"|'([^']*[\\u4e00-\\u9fff][^']*)'");

    private final RepositoryRepo repositoryRepo;
    private final ChunkRepo chunkRepo;
    private final CallGraphRepo callGraphRepo;
    private final BoundaryRepo boundaryRepo;
    private final ApiEndpointRepo apiEndpointRepo;
    private final SystemConfigRepo systemConfigRepo;
    private final BuildLogService buildLogService;
    private final BoundaryDetectorImpl boundaryDetector;
    private final ConfigValueExtractor configExtractor;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final CallGraphEngine callGraphEngine;
    private final RepoConfigRepo repoConfigRepo;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingService embeddingService;

    @Value("${platform.analysis-output-dir:./data/analysis}")
    private String analysisOutputDir;

    public BytecodeAnalyzerImpl(RepositoryRepo repositoryRepo, ChunkRepo chunkRepo,
                                 CallGraphRepo callGraphRepo, BoundaryRepo boundaryRepo,
                                 ApiEndpointRepo apiEndpointRepo, SystemConfigRepo systemConfigRepo,
                                 BuildLogService buildLogService,
                                 TransactionTemplate transactionTemplate,
                                 BoundaryDetectorImpl boundaryDetector,
                                 ConfigValueExtractor configExtractor,
                                 ProjectInfoExtractor projectInfoExtractor,
                                 CallGraphEngine callGraphEngine,
                                 RepoConfigRepo repoConfigRepo,
                                 EmbeddingService embeddingService) {
        this.repositoryRepo = repositoryRepo;
        this.chunkRepo = chunkRepo;
        this.callGraphRepo = callGraphRepo;
        this.boundaryRepo = boundaryRepo;
        this.apiEndpointRepo = apiEndpointRepo;
        this.systemConfigRepo = systemConfigRepo;
        this.buildLogService = buildLogService;
        this.transactionTemplate = transactionTemplate;
        this.boundaryDetector = boundaryDetector;
        this.configExtractor = configExtractor;
        this.projectInfoExtractor = projectInfoExtractor;
        this.callGraphEngine = callGraphEngine;
        this.repoConfigRepo = repoConfigRepo;
        this.embeddingService = embeddingService;
    }

    @Override
    public AnalysisResult analyzeFullProject(Long repoId, boolean forceRebuild) {
        RepositoryEntity repo = repositoryRepo.findById(repoId)
                .orElseThrow(() -> new AnalysisException("仓库不存在: " + repoId));

        repo.setStatus("ANALYZING");
        repositoryRepo.save(repo);
        buildLogService.clear(repoId);
        buildLogService.append(repoId, "🔍 开始分析仓库: " + repo.getName());

        try {
            // 1. 查找编译产物（jar/class 文件），如果没有则先尝试编译
            if (forceRebuild) {
                // 先 pull 最新代码
                if (!"LOCAL".equals(repo.getRepoType())) {
                    buildLogService.append(repoId, "📥 拉取最新代码...");
                    try (Git git = Git.open(new File(repo.getLocalPath()))) {
                        var pullCmd = git.pull();
                        String token = repo.getTokenEncrypted();
                        if (token != null && !token.isBlank()) {
                            String repoType = repo.getRepoType();
                            String username = "GITLAB".equalsIgnoreCase(repoType) ? "oauth2" : token;
                            pullCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
                        }
                        var pullResult = pullCmd.call();
                        if (pullResult.isSuccessful()) {
                            String newHash = git.getRepository().resolve("HEAD").getName();
                            buildLogService.append(repoId, "✅ 拉取成功: " + newHash.substring(0, 8));
                            repo.setLastCommitHash(newHash);
                            repo.setLastSyncTime(LocalDateTime.now());
                            repositoryRepo.save(repo);
                        } else {
                            buildLogService.append(repoId, "⚠️ 拉取有冲突，使用当前代码继续");
                        }
                    } catch (Exception e) {
                        buildLogService.append(repoId, "⚠️ 拉取失败: " + e.getMessage() + "，使用当前代码继续");
                        logger.warn("pull 失败: {}", repo.getName(), e);
                    }
                }

                buildLogService.append(repoId, "🔄 强制重新编译...");
                // 清理旧的收集目录
                Path collectedDir = Path.of(repo.getLocalPath(), "_collected_jars");
                if (Files.isDirectory(collectedDir)) {
                    try (var walk = Files.walk(collectedDir)) {
                        walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    }
                }
                triggerBuild(repo.getLocalPath(), repoId);
            }
            String jarPath = findBuildOutput(repo.getLocalPath());
            if (jarPath == null) {
                logger.info("未找到编译产物，尝试自动编译: {}", repo.getName());
                buildLogService.append(repoId, "📦 未找到编译产物，开始自动编译...");
                triggerBuild(repo.getLocalPath(), repoId);
                jarPath = findBuildOutput(repo.getLocalPath());
                if (jarPath == null) {
                    throw new AnalysisException("编译后仍未找到编译产物，请检查项目是否能正常编译，或手动上传 jar 文件");
                }
            }
            buildLogService.append(repoId, "📂 编译产物路径: " + jarPath);

            // 2. 配置并执行 javacg2 分析
            String outputDir = getOutputDir(repoId);
            Files.createDirectories(Path.of(outputDir));

            JavaCG2ConfigureWrapper wrapper = new JavaCG2ConfigureWrapper(true);
            wrapper.setOtherConfigList(JavaCG2OtherConfigFileUseListEnum.OCFULE_JAR_DIR, jarPath);
            wrapper.setMainConfig(JavaCG2ConfigKeyEnum.CKE_OUTPUT_ROOT_PATH, outputDir);
            wrapper.setMainConfig(JavaCG2ConfigKeyEnum.CKE_CONTINUE_WHEN_ERROR, Boolean.TRUE.toString());
            wrapper.setMainConfig(JavaCG2ConfigKeyEnum.CKE_PARSE_METHOD_CALL_TYPE_VALUE, Boolean.TRUE.toString());
            wrapper.setMainConfig(JavaCG2ConfigKeyEnum.CKE_FIRST_PARSE_INIT_METHOD_TYPE, Boolean.TRUE.toString());
            // 跳过会产生大量栈桢快照、对调用链分析无价值的合成/生成方法
            // 包含：Lombok(equals/hashCode/toString/canEqual)、JVM lambda合成($deserializeLambda$)、Protobuf生成(getSerializedSize/mergeFrom/writeTo/parseFrom/dynamicMethod)
            wrapper.setElConfigText(JavaCG2ElConfigEnum.ECE_PARSE_IGNORE_METHOD,
                    "method_name == 'equals' || method_name == 'hashCode' || method_name == 'toString' || method_name == 'canEqual'" +
                    " || method_name == '$deserializeLambda$'" +
                    " || method_name == 'getSerializedSize' || method_name == 'getSerializedSizeAsMessageLite'" +
                    " || method_name == 'mergeFrom' || method_name == 'writeTo' || method_name == 'parseFrom'" +
                    " || method_name == 'dynamicMethod' || method_name == 'newBuilderForType' || method_name == 'newBuilder'");
            // 若配置了包前缀，则只分析用户自己的类，跳过第三方依赖
            String packagePrefix = configExtractor.getEffectiveValue(repoId, "analyze.package.prefix", null);
            if (packagePrefix != null && !packagePrefix.isBlank()) {
                buildLogService.append(repoId, "📦 仅分析包前缀: " + packagePrefix);
                // 支持多个包前缀，逗号分隔
                String[] prefixes = packagePrefix.split("[,;\\s]+");
                StringBuilder expr = new StringBuilder();
                for (int i = 0; i < prefixes.length; i++) {
                    String p = prefixes[i].trim();
                    if (p.isEmpty()) continue;
                    if (expr.length() > 0) expr.append(" && ");
                    expr.append("!string.startsWith(class_name, '").append(p).append("')");
                }
                if (expr.length() > 0) {
                    wrapper.setElConfigText(JavaCG2ElConfigEnum.ECE_PARSE_IGNORE_CLASS, expr.toString());
                }
            }

            logger.info("开始 javacg2 分析: repo={}, jar={}", repo.getName(), jarPath);
            buildLogService.append(repoId, "\n🔬 开始 javacg2 字节码分析...");
            buildLogService.append(repoId, "分析目标: " + jarPath);

            // 列出要分析的 jar 文件
            File jarFile = new File(jarPath);
            if (jarFile.isDirectory()) {
                File[] jars = jarFile.listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".war"));
                if (jars != null) {
                    buildLogService.append(repoId, "包含 " + jars.length + " 个 jar 文件:");
                    for (File j : jars) {
                        buildLogService.append(repoId, "  - " + j.getName() + " (" + (j.length() / 1024) + " KB)");
                    }
                }
            }

            buildLogService.append(repoId, "⏳ javacg2 分析中，请耐心等待（大项目可能需要几分钟）...");
            long analysisStart = System.currentTimeMillis();
            JavaCG2Entry entry = new JavaCG2Entry(wrapper);
            boolean success = entry.run();
            long analysisDuration = (System.currentTimeMillis() - analysisStart) / 1000;

            if (!success) {
                throw new AnalysisException("javacg2 分析失败");
            }
            buildLogService.append(repoId, "✅ javacg2 字节码分析完成（耗时 " + analysisDuration + " 秒）");

            // 3. 找到实际输出目录（javacg2 会在 outputDir 下创建子目录）
            String actualOutputDir = findActualOutputDir(outputDir);
            if (actualOutputDir == null) {
                throw new AnalysisException("未找到 javacg2 输出文件");
            }

            // 4-6. 清除旧数据、导入、更新状态（需要事务，用 TransactionTemplate 避免同类调用代理失效）
            buildLogService.append(repoId, "🗑️  清除旧数据...");
            int[] counts = transactionTemplate.execute(status -> importAnalysisResult(repoId, actualOutputDir, repo));
            int methodCount = counts[0], callCount = counts[1];

            logger.info("分析完成: repo={}, methods={}, calls={}", repo.getName(), methodCount, callCount);
            buildLogService.append(repoId, "\n📊 分析完成: 方法 " + methodCount + " 个, 调用关系 " + callCount + " 条");
            buildLogService.finish(repoId, true);
            return new AnalysisResult(true, "分析完成", methodCount, callCount);

        } catch (AnalysisException e) {
            repo.setStatus("ERROR");
            repositoryRepo.save(repo);
            buildLogService.append(repoId, "\n❌ " + e.getMessage());
            buildLogService.finish(repoId, false);
            throw e;
        } catch (Exception e) {
            repo.setStatus("ERROR");
            repositoryRepo.save(repo);
            logger.error("分析失败: {}", repo.getName(), e);
            buildLogService.append(repoId, "\n❌ 分析失败: " + e.getMessage());
            buildLogService.finish(repoId, false);
            throw new AnalysisException("分析失败: " + e.getMessage());
        }
    }

    public int[] importAnalysisResult(Long repoId, String actualOutputDir, RepositoryEntity repo) {
        // 4. 清除旧数据
        chunkRepo.deleteByRepoId(repoId);
        callGraphRepo.deleteByRepoId(repoId);
        boundaryRepo.deleteByRepoId(repoId);
        apiEndpointRepo.deleteByRepoId(repoId);

        // 5. 解析输出文件并导入数据库
        buildLogService.append(repoId, "📥 导入方法信息...");
        int methodCount = parseMethodInfo(repoId, actualOutputDir);
        buildLogService.append(repoId, "📥 导入行号信息...");
        parseMethodLineNumber(repoId, actualOutputDir);
        buildLogService.append(repoId, "📥 导入注解信息...");
        parseMethodAnnotation(repoId, actualOutputDir);
        buildLogService.append(repoId, "📥 导入调用关系...");
        int callCount = parseMethodCall(repoId, actualOutputDir);
        buildLogService.append(repoId, "📥 导入继承/实现关系...");
        parseExtendsImpl(repoId, actualOutputDir);
        buildLogService.append(repoId, "📥 导入异常处理...");
        parseMethodCatch(repoId, actualOutputDir, repo.getLocalPath());
        parseMethodThrow(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "📥 导入 Spring 控制器...");
        parseSpringController(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "📥 识别消息监听/定时任务/gRPC 入口...");
        parseListenerEndpoints(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "📥 导入 Spring Bean...");
        parseSpringBean(repoId, actualOutputDir);

        // 5.5 边界点检测（HTTP/gRPC/MQ/DB/缓存/序列化等）
        buildLogService.append(repoId, "🔍 检测边界点（HTTP/gRPC/MQ/DB/缓存/序列化）...");
        int boundaryCount = boundaryDetector.detectBoundaries(repoId, repo.getLocalPath());
        buildLogService.append(repoId, "✅ 检测到 " + boundaryCount + " 个边界点");

        // 5.6 充实 call_summary 搜索索引（常量、注释、异常、URL等）
        buildLogService.append(repoId, "📝 充实搜索索引...");
        enrichCallSummary(repoId, actualOutputDir, repo.getLocalPath());

        // 5.6.1 提取调用链展示用的干净结构化数据（常量/异常/解析后的URL）
        enrichStructuredData(repoId, actualOutputDir, repo.getLocalPath());

        // 5.7 生成仓库画像（用于多仓库场景快速定位）
        buildLogService.append(repoId, "🏠 生成仓库画像...");
        buildRepoProfile(repoId, actualOutputDir, repo);

        // 5.8 生成项目概览文档（仅首次或概览为空时生成）
        if (repo.getOverview() == null || repo.getOverview().isBlank()) {
            buildLogService.append(repoId, "📖 生成项目概览文档...");
            generateOverview(repoId, actualOutputDir, repo);
        } else {
            buildLogService.append(repoId, "📖 项目概览文档已存在，跳过生成");
        }

        // 清空源码缓存
        sourceFileCache.clear();

        // 6. 更新仓库状态
        repo.setStatus("READY");
        repo.setLastSyncTime(LocalDateTime.now());
        repositoryRepo.save(repo);

        // 7. 触发后台向量索引（选配功能）。向量建设完全独立于调用链分析：
        //    它只写应用日志、不写本构建日志流，失败也不影响调用链分析结果。
        try {
            if (!embeddingService.isConfigured()) {
                buildLogService.append(repoId, "ℹ️ 未配置 Embedding，跳过向量索引；语义搜索将降级为关键词匹配");
                logger.info("[Embedding] 未配置，跳过向量索引 repoId={}", repoId);
            } else {
                buildLogService.append(repoId, "🔢 向量索引将在后台异步建设（不影响调用链分析，进度见应用日志 / 向量建设页）");
                embeddingService.embedChunksBatchAsync(repoId);
            }
        } catch (Exception e) {
            // 选配功能，任何异常都不能影响调用链分析
            logger.warn("[Embedding] 提交向量任务失败，不影响分析结果: {}", e.getMessage());
        }

        return new int[]{methodCount, callCount};
    }

    // ========== TSV 解析方法 ==========

    @Override
    public String getOutputDir(Long repoId) {
        return Path.of(analysisOutputDir, "repo_" + repoId).toString();
    }

    @Override
    @Transactional
    public void rebuildIndex(Long repoId) {
        RepositoryEntity repo = repositoryRepo.findById(repoId)
                .orElseThrow(() -> new AnalysisException("REPO_NOT_FOUND", "仓库不存在", ""));
        if (!"READY".equals(repo.getStatus())) {
            throw new AnalysisException("REPO_NOT_READY", "仓库未分析完成，无法补建索引", "");
        }

        String outputDir = getOutputDir(repoId);
        String actualOutputDir = findActualOutputDir(outputDir);
        if (actualOutputDir == null) {
            throw new AnalysisException("OUTPUT_NOT_FOUND", "分析产物目录不存在", "");
        }

        logger.info("补建索引开始: repoId={}, outputDir={}", repoId, actualOutputDir);
        buildLogService.append(repoId, "🔄 补建搜索索引和仓库画像...");

        // 先清空已有的 call_summary，避免重复追加
        List<ChunkEntity> allChunks = chunkRepo.findByRepoId(repoId);
        for (ChunkEntity chunk : allChunks) {
            chunk.setCallSummary(null);
        }
        chunkRepo.saveAll(allChunks);

        // 重新解析注解（修复列索引后重新导入）
        parseMethodAnnotation(repoId, actualOutputDir);
        buildLogService.append(repoId, "✅ 注解信息已更新");

        // 重新识别入口点（修复源码过滤 + accessFlags 后重新识别）
        apiEndpointRepo.deleteByRepoId(repoId);
        parseSpringController(repoId, actualOutputDir, repo.getLocalPath());
        parseListenerEndpoints(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "✅ 入口点已重新识别");

        // 清空源码缓存
        sourceFileCache.clear();

        // 充实 call_summary
        enrichCallSummary(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "✅ 搜索索引已充实");

        // 提取调用链展示用的干净结构化数据（常量/异常/解析后的URL）
        enrichStructuredData(repoId, actualOutputDir, repo.getLocalPath());
        buildLogService.append(repoId, "✅ 结构化数据已提取");

        // 生成仓库画像
        buildRepoProfile(repoId, actualOutputDir, repo);

        // 生成项目概览文档（仅当为空时）
        if (repo.getOverview() == null || repo.getOverview().isBlank()) {
            generateOverview(repoId, actualOutputDir, repo);
        }

        buildLogService.append(repoId, "✅ 补建索引完成");

        logger.info("补建索引完成: repoId={}", repoId);
    }

    // ========== 查找编译产物 ==========

    private void triggerBuild(String repoPath, Long repoId) {
        File repoDir = new File(repoPath);
        File gradlew = new File(repoDir, "gradlew");
        File pomXml = new File(repoDir, "pom.xml");
        File buildGradle = new File(repoDir, "build.gradle");

        // 读取用户配置的 Maven 信息
        String settingsPath = systemConfigRepo.findByConfigKey("maven.settings.path")
                .map(c -> c.getConfigValue()).orElse(null);
        String localRepo = systemConfigRepo.findByConfigKey("maven.repo.local")
                .map(c -> c.getConfigValue()).orElse(null);
        String mavenHome = systemConfigRepo.findByConfigKey("maven.home")
                .map(c -> c.getConfigValue()).orElse(null);
        // 编译用 JDK：仓库级配置 > 系统级配置 > 自动探测(读 pom 版本) > 系统默认 java。
        // 老的 gRPC/protobuf 项目依赖 javax.annotation.Generated（JDK 11+ 已移除），必须用 JDK 8 编译，
        // 否则报「找不到符号 类 Generated」。
        String javaHome = configExtractor.getEffectiveValue(repoId, "build.java.home",
                systemConfigRepo.findByConfigKey("build.java.home").map(c -> c.getConfigValue()).orElse(null));
        boolean javaHomeAutoDetected = false;
        if (javaHome == null || javaHome.isBlank()) {
            javaHome = autoDetectJavaHome(repoDir, pomXml);
            javaHomeAutoDetected = javaHome != null;
        }

        List<String> command = new ArrayList<>();

        if (pomXml.exists()) {
            // Maven 项目：使用用户配置的 settings 和 repository
            String mvnCmd = "mvn";
            if (mavenHome != null && !mavenHome.isBlank()) {
                mvnCmd = mavenHome + "/bin/mvn";
            }
            command.add(mvnCmd);
            command.add("package");
            command.add("-DskipTests");
            command.add("-Dmaven.test.skip=true");      // 彻底跳过测试编译和执行
            command.add("-Dmaven.javadoc.skip=true");    // 跳过 javadoc
            command.add("-Dmaven.source.skip=true");     // 跳过 source 打包
            command.add("-Dcheckstyle.skip=true");       // 跳过 checkstyle
            command.add("-Dpmd.skip=true");              // 跳过 pmd
            command.add("-Dspotbugs.skip=true");         // 跳过 spotbugs
            command.add("-Denforcer.skip=true");         // 跳过 enforcer
            command.add("--fail-at-end");                // 尽量多编译模块
            if (settingsPath != null && !settingsPath.isBlank()) {
                command.add("-s");
                command.add(settingsPath);
            }
            if (localRepo != null && !localRepo.isBlank()) {
                command.add("-Dmaven.repo.local=" + localRepo);
            }
        } else if (gradlew.exists()) {
            gradlew.setExecutable(true);
            command.add("./gradlew");
            command.add("build");
            command.add("-x");
            command.add("test");
        } else if (buildGradle.exists()) {
            command.add("gradle");
            command.add("build");
            command.add("-x");
            command.add("test");
        } else {
            logger.warn("未找到构建文件 (pom.xml/gradlew/build.gradle): {}", repoPath);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);

        // 设置环境变量
        Map<String, String> env = pb.environment();
        if (mavenHome != null && !mavenHome.isBlank()) {
            env.put("M2_HOME", mavenHome);
            env.put("MAVEN_HOME", mavenHome);
            // 把 maven/bin 加到 PATH 前面
            String path = env.getOrDefault("PATH", "");
            env.put("PATH", mavenHome + "/bin:" + path);
        }
        // 指定编译用 JDK：Maven/Gradle 均认 JAVA_HOME，再把其 bin 提到 PATH 最前
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("JAVA_HOME", javaHome);
            String path = env.getOrDefault("PATH", "");
            env.put("PATH", javaHome + "/bin:" + path);
            String tag = javaHomeAutoDetected ? "（自动探测）" : "";
            logger.info("使用指定 JDK 编译{}: {}", tag, javaHome);
            buildLogService.append(repoId, "☕ 使用 JDK 编译" + tag + ": " + javaHome);
        }

        try {
            logger.info("开始编译: {} | 命令: {}", repoPath, String.join(" ", command));
            if (settingsPath != null) logger.info("使用 settings.xml: {}", settingsPath);
            if (localRepo != null) logger.info("使用本地仓库: {}", localRepo);
            buildLogService.append(repoId, "$ " + String.join(" ", command));

            Process process = pb.start();
            // 实时打印编译日志
            Thread logThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[编译] {}", line);
                        buildLogService.append(repoId, line);
                    }
                } catch (IOException e) {
                    logger.warn("读取编译日志失败", e);
                }
            });
            logThread.setDaemon(true);
            logThread.start();

            int exitCode = process.waitFor();
            logThread.join(5000);

            if (exitCode == 0) {
                logger.info("编译成功: {}", repoPath);
                buildLogService.append(repoId, "\n✅ 编译成功");
            } else {
                logger.warn("编译部分失败 (exit code: {})，尝试使用已有产物继续分析", exitCode);
                buildLogService.append(repoId, "\n⚠️ 编译部分失败 (exit code: " + exitCode + ")，尝试使用已有产物继续分析...");
            }
        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            logger.error("编译触发失败: {}", repoPath, e);
            throw new AnalysisException("编译触发失败: " + e.getMessage());
        }
    }

    /**
     * 自动探测编译用 JDK：解析项目声明的 Java 版本，用 macOS 的 java_home 找对应 JDK。
     * 找不到则返回 null（退回系统默认 java）。
     */
    private String autoDetectJavaHome(File repoDir, File pomXml) {
        String version = detectProjectJavaVersion(repoDir, pomXml);
        if (version == null) return null;
        String home = resolveJavaHomeByVersion(version);
        if (home != null) {
            logger.info("自动探测：项目 Java 版本={} → JDK {}", version, home);
        } else {
            logger.warn("自动探测：项目声明 Java 版本={}，但本机未找到对应 JDK，退回系统默认 java", version);
        }
        return home;
    }

    /** 解析项目声明的 Java 版本（如 "1.8" / "8" / "11" / "17"）。Maven 读 pom，Gradle 读 build.gradle。 */
    private String detectProjectJavaVersion(File repoDir, File pomXml) {
        try {
            if (pomXml.exists()) {
                String pom = Files.readString(pomXml.toPath());
                // 优先 <maven.compiler.release> / <source> / <target>，再 <java.version>
                for (String tag : new String[]{"maven.compiler.release", "maven.compiler.source",
                        "maven.compiler.target", "java.version"}) {
                    var m = java.util.regex.Pattern.compile("<" + java.util.regex.Pattern.quote(tag) + ">\\s*([\\d.]+)\\s*</")
                            .matcher(pom);
                    if (m.find()) return m.group(1).trim();
                }
            }
            File buildGradle = new File(repoDir, "build.gradle");
            File buildGradleKts = new File(repoDir, "build.gradle.kts");
            File gradleFile = buildGradle.exists() ? buildGradle : (buildGradleKts.exists() ? buildGradleKts : null);
            if (gradleFile != null) {
                String gradle = Files.readString(gradleFile.toPath());
                // sourceCompatibility = '1.8' / JavaVersion.VERSION_17 / JavaLanguageVersion.of(17)
                var m = java.util.regex.Pattern.compile(
                        "(?:sourceCompatibility|targetCompatibility|JavaVersion\\.VERSION_|JavaLanguageVersion\\.of\\(|languageVersion[^\\d]*)['\"]?_?([\\d.]+)")
                        .matcher(gradle);
                if (m.find()) return m.group(1).replace('_', '.').trim();
            }
        } catch (IOException e) {
            logger.debug("解析项目 Java 版本失败", e);
        }
        return null;
    }

    /** 把项目 Java 版本规整为 major（1.8→8, 17→17），用 /usr/libexec/java_home 找含 javac 的 JDK 根目录。 */
    private String resolveJavaHomeByVersion(String rawVersion) {
        String v = rawVersion.startsWith("1.") ? rawVersion.substring(2) : rawVersion;
        if (v.contains(".")) v = v.substring(0, v.indexOf('.'));
        // java_home 对 8 认 "1.8"，对 9+ 认 major 号
        String query = "8".equals(v) ? "1.8" : v;
        try {
            // -V 列出所有匹配项（含 JRE 与 JDK）；-v 只返回默认那个，可能是没有 javac 的 JRE。
            // 用 -X（XML）拿到全部 JVMHomePath，逐个挑出真正含 javac（编译器）的 JDK。
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", query, "-X")
                    .redirectErrorStream(false).start();
            StringBuilder sb = new StringBuilder();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            // 从 XML 里抓所有 JVMHomePath
            var m = java.util.regex.Pattern.compile("<key>JVMHomePath</key>\\s*<string>([^<]+)</string>")
                    .matcher(sb.toString());
            while (m.find()) {
                String home = m.group(1).trim();
                if (isJdkWithCompiler(home)) return home;
            }
            // 退而求其次：用 -v 拿默认项，仍要求含 javac
            Process p2 = new ProcessBuilder("/usr/libexec/java_home", "-v", query)
                    .redirectErrorStream(false).start();
            String out;
            try (var r = new BufferedReader(new InputStreamReader(p2.getInputStream()))) {
                out = r.readLine();
            }
            p2.waitFor();
            if (out != null && isJdkWithCompiler(out.trim())) return out.trim();
        } catch (Exception e) {
            logger.debug("java_home 探测失败 (version={})", query, e);
        }
        return null;
    }

    /** 校验该路径是真正的 JDK（含 javac 编译器），排除只有 java 的 JRE。 */
    private boolean isJdkWithCompiler(String home) {
        if (home == null || home.isBlank()) return false;
        return new File(home, "bin/javac").exists() || new File(home, "bin/javac.exe").exists();
    }

    private String findBuildOutput(String repoPath) {
        Path collectedDir = Path.of(repoPath, "_collected_jars");
        Path uploadedDir = Path.of(repoPath, "uploaded_jars");

        try {
            // 清理旧的收集目录
            if (Files.isDirectory(collectedDir)) {
                try (var walk = Files.walk(collectedDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
            Files.createDirectories(collectedDir);

            // 第一步：收集编译产物（基础）
            Map<String, Path> jarMap = new LinkedHashMap<>(); // fileName -> path

            // Maven target/*.jar
            try (var walk = Files.walk(Path.of(repoPath), 5)) {
                walk.filter(p -> {
                    String name = p.getFileName().toString();
                    String parent = p.getParent() != null ? p.getParent().getFileName().toString() : "";
                    return name.endsWith(".jar") && "target".equals(parent)
                            && !name.contains("-sources") && !name.contains("-javadoc")
                            && !name.contains("-tests") && !name.startsWith("original-");
                }).forEach(p -> jarMap.put(p.getFileName().toString(), p));
            }

            // Gradle build/libs/*.jar
            try (var walk = Files.walk(Path.of(repoPath), 5)) {
                walk.filter(p -> {
                    String name = p.getFileName().toString();
                    Path pp = p.getParent();
                    return name.endsWith(".jar") && pp != null && "libs".equals(pp.getFileName().toString())
                            && pp.getParent() != null && "build".equals(pp.getParent().getFileName().toString())
                            && !name.contains("-sources") && !name.contains("-javadoc") && !name.contains("-tests");
                }).forEach(p -> jarMap.put(p.getFileName().toString(), p));
            }

            logger.info("编译产物: {} 个 jar", jarMap.size());

            // 第二步：上传的 jar 按文件名覆盖（增量替换）
            int uploadedCount = 0;
            if (Files.isDirectory(uploadedDir)) {
                try (var stream = Files.list(uploadedDir)) {
                    var uploadedFiles = stream
                            .filter(p -> p.toString().endsWith(".jar") || p.toString().endsWith(".war"))
                            .toList();
                    for (Path uploaded : uploadedFiles) {
                        String name = uploaded.getFileName().toString();
                        // 按文件名匹配替换，或者作为新增
                        jarMap.put(name, uploaded);
                        uploadedCount++;
                        logger.info("  上传覆盖: {}", name);
                    }
                }
            }

            if (jarMap.isEmpty()) {
                return null;
            }

            // 第三步：复制到收集目录，记录来源
            StringBuilder manifest = new StringBuilder();
            for (Map.Entry<String, Path> entry : jarMap.entrySet()) {
                Path source = entry.getValue();
                Path target = collectedDir.resolve(entry.getKey());
                if (Files.exists(target)) {
                    // 同名冲突加前缀
                    String parent = source.getParent() != null ? source.getParent().getFileName().toString() : "dup";
                    target = collectedDir.resolve(parent + "_" + entry.getKey());
                }
                Files.copy(source, target);

                boolean isUploaded = source.startsWith(uploadedDir);
                String tag = isUploaded ? "[上传]" : "[编译]";
                manifest.append(tag).append(" ").append(entry.getKey()).append("\n");
                logger.info("  {} {}", tag, entry.getKey());
            }

            // 写入 manifest 文件供前端读取
            Files.writeString(collectedDir.resolve("_manifest.txt"), manifest.toString());

            logger.info("最终 jar 列表: 编译 {} + 上传 {} = 共 {} 个",
                    jarMap.size() - uploadedCount, uploadedCount, jarMap.size());

            return collectedDir.toString();

        } catch (IOException e) {
            logger.error("收集 jar 文件失败", e);
            return null;
        }
    }

    private String findActualOutputDir(String outputDir) {
        // javacg2 在 outputDir 下创建以时间戳命名的子目录
        try (var stream = Files.list(Path.of(outputDir))) {
            return stream.filter(Files::isDirectory)
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ========== TSV 解析方法 ==========

    private int parseMethodInfo(Long repoId, String outputDir) {
        // method_info: 完整方法 | access_flags | 返回类型 | 数组维度 | 类型分类 | 泛型 | MD5 | jar序号
        int count = 0;
        List<ChunkEntity> batch = new ArrayList<>();
        // 同一 fullMethod 可能出现在多个 jar 中，只取第一条
        Set<String> seen = new java.util.HashSet<>();
        for (String line : readTsvFile(outputDir, "method_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 8) continue;
            if (!seen.add(cols[0])) continue;

            ChunkEntity chunk = new ChunkEntity();
            chunk.setRepoId(repoId);
            chunk.setFullMethod(cols[0]);
            chunk.setAccessFlags(parseAccessFlags(cols[1]));
            chunk.setReturnType(cols[2]);
            chunk.setMethodHash(cols[6].isEmpty() ? null : cols[6]);
            chunk.setJarNum(parseIntSafe(cols[7]));

            // 从完整方法签名中提取类名、包名、方法名
            parseMethodSignature(chunk, cols[0]);

            batch.add(chunk);
            count++;

            if (batch.size() >= 500) {
                chunkRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            chunkRepo.saveAll(batch);
        }
        logger.info("解析 method_info: {} 条", count);
        return count;
    }

    private void parseMethodLineNumber(Long repoId, String outputDir) {
        // method_line_number: 完整方法 | 返回类型 | 起始行号 | 结束行号
        // 先把该仓库所有 chunk 加载到内存 Map，避免逐行查库（N+1问题）
        Map<String, ChunkEntity> chunkMap = new HashMap<>();
        chunkRepo.findByRepoId(repoId).forEach(c -> chunkMap.put(c.getFullMethod(), c));

        int count = 0;
        List<ChunkEntity> batch = new ArrayList<>();
        for (String line : readTsvFile(outputDir, "method_line_number")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            ChunkEntity chunk = chunkMap.get(cols[0]);
            if (chunk != null) {
                chunk.setStartLine(parseIntSafe(cols[2]));
                chunk.setEndLine(parseIntSafe(cols[3]));
                batch.add(chunk);
                if (batch.size() >= 500) {
                    chunkRepo.saveAll(batch);
                    batch.clear();
                }
            }
            count++;
        }
        if (!batch.isEmpty()) chunkRepo.saveAll(batch);
        logger.info("解析 method_line_number: {} 条", count);
    }

    private void parseMethodAnnotation(Long repoId, String outputDir) {
        // method_annotation 实际格式: 完整方法 | 返回类型 | jar序号 | 注解类名 | 属性名 | 属性值
        Map<String, ChunkEntity> chunkMap = new HashMap<>();
        chunkRepo.findByRepoId(repoId).forEach(c -> chunkMap.put(c.getFullMethod(), c));

        Map<String, List<String>> annotationMap = new HashMap<>();
        Map<String, List<String>> descriptionMap = new HashMap<>();

        for (String line : readTsvFile(outputDir, "method_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            String fullMethod = cols[0];
            // cols[1]=returnType, cols[2]=jarNum, cols[3]=annotationClass
            String annotationClass = cols[3];
            annotationMap.computeIfAbsent(fullMethod, k -> new ArrayList<>()).add(annotationClass);

            // cols[4]=attrName, cols[5]=attrValue
            if (cols.length >= 6) {
                String attrName = cols[4];
                String attrValue = cols[5];
                if (attrValue != null && !attrValue.isBlank() && !attrName.isBlank()
                        && !attrValue.startsWith("org.") && !attrValue.startsWith("java.")
                        && !attrValue.startsWith("javax.") && !attrValue.startsWith("jakarta.")
                        && !attrValue.equals("GET") && !attrValue.equals("POST") && !attrValue.equals("PUT")
                        && !attrValue.equals("DELETE") && !attrValue.equals("PATCH")
                        && !attrValue.equals("true") && !attrValue.equals("false")
                        && attrValue.length() > 1 && attrValue.length() < 200) {
                    descriptionMap.computeIfAbsent(fullMethod, k -> new ArrayList<>()).add(attrValue);
                }
            }
        }

        // class_annotation 实际格式: 类名 | 注解类名 | 属性名 | 属性值
        Map<String, List<String>> classDescMap = new HashMap<>();
        for (String line : readTsvFile(outputDir, "class_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length >= 4) {
                String className = cols[0];
                String attrName = cols[2];
                String attrValue = cols[3];
                if (attrValue != null && !attrValue.isBlank()
                        && !attrValue.startsWith("org.") && !attrValue.startsWith("java.")
                        && !attrValue.startsWith("javax.") && !attrValue.startsWith("jakarta.")
                        && !attrValue.equals("true") && !attrValue.equals("false")
                        && attrValue.length() > 1 && attrValue.length() < 200) {
                    classDescMap.computeIfAbsent(className, k -> new ArrayList<>()).add(attrValue);
                }
            }
        }

        List<ChunkEntity> batch = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : annotationMap.entrySet()) {
            ChunkEntity chunk = chunkMap.get(entry.getKey());
            if (chunk != null) {
                chunk.setAnnotations(String.join(",", new LinkedHashSet<>(entry.getValue())));

                // 构建 call_summary：注解描述 + 类级别描述
                List<String> summaryParts = new ArrayList<>();
                List<String> methodDescs = descriptionMap.get(entry.getKey());
                if (methodDescs != null) summaryParts.addAll(methodDescs);
                // 加上类级别描述
                if (chunk.getClassName() != null) {
                    List<String> classDescs = classDescMap.get(chunk.getClassName());
                    if (classDescs != null) summaryParts.addAll(classDescs);
                }
                if (!summaryParts.isEmpty()) {
                    String summary = String.join(" ", new LinkedHashSet<>(summaryParts));
                    // 追加而不是覆盖（可能已有 AI 回填的摘要）
                    String existing = chunk.getCallSummary();
                    if (existing != null && !existing.isBlank()) {
                        chunk.setCallSummary(existing + " | " + summary);
                    } else {
                        chunk.setCallSummary(summary);
                    }
                }

                batch.add(chunk);
                if (batch.size() >= 500) {
                    chunkRepo.saveAll(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) chunkRepo.saveAll(batch);
        logger.info("解析 method_annotation: {} 个方法, {} 个有描述", annotationMap.size(), descriptionMap.size());
    }

    /**
     * 从源码中提取每个方法和类的 Javadoc/注释，存到 call_summary
     */
    private void extractSourceComments(Long repoId, String repoPath) {
        List<ChunkEntity> chunks = chunkRepo.findByRepoId(repoId);
        if (chunks.isEmpty()) return;

        // 按类名分组，同一个类的方法只读一次源码文件
        Map<String, List<ChunkEntity>> byClass = new LinkedHashMap<>();
        for (ChunkEntity chunk : chunks) {
            if (chunk.getClassName() != null) {
                byClass.computeIfAbsent(chunk.getClassName(), k -> new ArrayList<>()).add(chunk);
            }
        }

        int commentCount = 0;
        List<ChunkEntity> batch = new ArrayList<>();

        for (Map.Entry<String, List<ChunkEntity>> entry : byClass.entrySet()) {
            String className = entry.getKey();
            List<ChunkEntity> methods = entry.getValue();

            // 找源码文件
            String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            String relativePath = topLevel.replace('.', '/') + ".java";
            Path sourceFile = findSourceFileInRepo(Path.of(repoPath), relativePath);
            if (sourceFile == null) continue;

            try {
                List<String> lines = Files.readAllLines(sourceFile);

                // 提取类级别注释（文件开头到 class 声明之间的注释）
                String classComment = extractClassComment(lines);

                // 为每个方法提取方法上方的注释
                for (ChunkEntity chunk : methods) {
                    List<String> commentParts = new ArrayList<>();

                    // 加上类级别注释
                    if (classComment != null && !classComment.isBlank()) {
                        commentParts.add(classComment);
                    }

                    // 提取方法上方的注释
                    if (chunk.getStartLine() != null && chunk.getStartLine() > 0) {
                        String methodComment = extractMethodComment(lines, chunk.getStartLine());
                        if (methodComment != null && !methodComment.isBlank()) {
                            commentParts.add(methodComment);
                        }
                    } else {
                        // 没有行号，用方法名在源码中搜索
                        String methodComment = searchMethodComment(lines, chunk.getMethodName());
                        if (methodComment != null && !methodComment.isBlank()) {
                            commentParts.add(methodComment);
                        }
                    }

                    if (!commentParts.isEmpty()) {
                        String comment = String.join(" | ", commentParts);
                        // 追加到已有的 call_summary
                        String existing = chunk.getCallSummary();
                        if (existing != null && !existing.isBlank()) {
                            // 避免重复
                            if (!existing.contains(comment)) {
                                chunk.setCallSummary(existing + " | " + comment);
                            }
                        } else {
                            chunk.setCallSummary(comment);
                        }
                        batch.add(chunk);
                        commentCount++;

                        if (batch.size() >= 500) {
                            chunkRepo.saveAll(batch);
                            batch.clear();
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("读取源码提取注释失败: {}", sourceFile);
            }
        }

        if (!batch.isEmpty()) {
            chunkRepo.saveAll(batch);
        }
        logger.info("提取源码注释: {} 个方法有注释", commentCount);
        buildLogService.append(repoId, "提取源码注释: " + commentCount + " 个方法");
    }

    /**
     * 提取调用链展示用的「干净结构化数据」，与 call_summary 搜索索引分离。
     * 分别写入 chunks 表的 constants / exceptions / resolved_urls 列：
     *  - constants:     method_call_info 中的字符串常量（type=v, java.lang.String）
     *  - exceptions:    method_throw（抛出）+ method_catch（捕获）的异常短类名
     *  - resolved_urls: 通过「字段作为调用参数 → @Value 注解 → 配置值」数据流解析出的外部调用 URL
     */
    private void enrichStructuredData(Long repoId, String outputDir, String repoPath) {
        Map<String, ChunkEntity> chunkMap = new HashMap<>();
        chunkRepo.findByRepoId(repoId).forEach(c -> chunkMap.put(c.getFullMethod(), c));

        ObjectMapper jsonMapper = new ObjectMapper();
        // 源码文件行缓存：类名 → 该文件所有行
        Map<String, List<String>> srcCache = new HashMap<>();

        // 每个方法的常量值（保持顺序、去重）—— 只收集静态常量/枚举引用，不再收集字面量值
        // 字面量值（字符串"courseId is empty"、数字0等）已在「入参绑定」和「错误码」区展示，常量区只展示有名称的引用
        Map<String, Set<String>> methodConstants = new HashMap<>();
        // 每个方法的异常：列表，每项 [kind(throws/catch), type, lineStr]
        Map<String, List<String[]>> methodExceptions = new HashMap<>();
        // 每个方法的 URL：列表，每项 [url, configKey, field]
        Map<String, List<String[]>> methodUrls = new HashMap<>();

        // 1. 枚举/静态常量引用：method_call_static_field（callId|?|?|fieldClass|fieldName|fieldType|caller|returnType）
        //    只保留首字母大写的字段名（枚举常量如 ROLE_TYPE_STUDENT / 静态常量如 APPLICATION_JSON），
        //    过滤 log/logger/httpClient 等小写实例字段噪音
        for (String line : readTsvFile(outputDir, "method_call_static_field")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;
            String fieldClass = cols[3];
            String fieldName = cols[4];
            String caller = cols[6];
            if (fieldName == null || fieldName.isBlank()) continue;
            if (!Character.isUpperCase(fieldName.charAt(0))) continue;   // 仅枚举/静态常量
            // 过滤日志相关（log/LOG/logger）
            if (fieldName.equals("LOG") || fieldName.equals("LOGGER")) continue;
            methodConstants.computeIfAbsent(caller, k -> new LinkedHashSet<>())
                    .add(shortName(fieldClass) + "." + fieldName);
        }

        // 2. 抛出的异常：method_throw（[3]throw行号 [5]异常类型）
        for (String line : readTsvFile(outputDir, "method_throw")) {
            String[] cols = line.split("\t");
            if (cols.length < 6) continue;
            String caller = cols[0];
            String exType = cols[5];
            if (exType == null || exType.isBlank()) continue;
            methodExceptions.computeIfAbsent(caller, k -> new ArrayList<>())
                    .add(new String[]{"throws", shortName(exType), cols[3]});
        }

        // 3. 捕获的异常：method_catch（[2]异常类型 [3]标志 [10]catch行号）
        for (String line : readTsvFile(outputDir, "method_catch")) {
            String[] cols = line.split("\t");
            if (cols.length < 11) continue;
            String caller = cols[0];
            String exType = cols[2];
            String flag = cols[3];
            if (flag != null && !flag.isBlank()) continue;   // 跳过编译器生成的 switch/try-with-resource
            if (exType == null || exType.isBlank()) continue;
            methodExceptions.computeIfAbsent(caller, k -> new ArrayList<>())
                    .add(new String[]{"catch", shortName(exType), cols[10]});
        }

        // 4. URL 数据流解析：field_annotation(@Value) + method_call_non_static_field(字段作为参数) + 配置值
        Map<String, String> fieldValueKey = new HashMap<>();   // "className#fieldName" → 配置key
        for (String line : readTsvFile(outputDir, "field_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 5) continue;
            String className = cols[0];
            String fieldName = cols[1];
            String annotationClass = cols[2];
            String attrValue = cols[4];
            if (annotationClass == null || !annotationClass.contains("Value")) continue;
            if (attrValue == null) continue;
            String key = extractPlaceholderKey(attrValue);
            if (key != null) fieldValueKey.put(className + "#" + fieldName, key);
        }
        if (!fieldValueKey.isEmpty()) {
            Set<String> urlDedup = new HashSet<>();
            for (String line : readTsvFile(outputDir, "method_call_non_static_field")) {
                String[] cols = line.split("\t");
                if (cols.length < 7) continue;
                String fieldName = cols[4];
                String caller = cols[6];
                if (fieldName == null || fieldName.isBlank()) continue;
                String callerClass = caller.contains(":") ? caller.substring(0, caller.lastIndexOf(':')) : caller;
                String cfgKey = fieldValueKey.get(callerClass + "#" + fieldName);
                if (cfgKey != null && urlDedup.add(caller + "#" + cfgKey)) {
                    String resolved = configExtractor.getEffectiveValue(repoId, cfgKey, null);
                    String url = (resolved != null && !resolved.isBlank()) ? resolved : "(未配置)";
                    methodUrls.computeIfAbsent(caller, k -> new ArrayList<>())
                            .add(new String[]{url, cfgKey, fieldName});
                }
            }
        }
        // 4c. 硬编码 URL 常量
        for (Map.Entry<String, Set<String>> e : methodConstants.entrySet()) {
            for (String c : e.getValue()) {
                if (c.startsWith("http://") || c.startsWith("https://")) {
                    methodUrls.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(new String[]{c, null, null});
                }
            }
        }

        // 4d. 解析枚举常量的构造参数值（enum_init_assign_info）：用于把 XxxEnum.CONST 还原成 code + msg
        //     格式: enumClass:constructor | constName | ordinal | argSeq | valueType | arrayDim | value
        Map<String, java.util.TreeMap<Integer, String>> enumArgs = new HashMap<>();
        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;
            String enumClass = cols[0].contains(":") ? cols[0].substring(0, cols[0].indexOf(':')) : cols[0];
            String key = shortName(enumClass) + "." + cols[1];
            Integer argSeq = parseIntSafe(cols[3]);
            if (argSeq == null) continue;
            enumArgs.computeIfAbsent(key, k -> new java.util.TreeMap<>()).put(argSeq, cols[6]);
        }

        // 5. 写入 chunks 表（带源码引用的 JSON）
        List<ChunkEntity> batch = new ArrayList<>();
        Set<String> allMethods = new HashSet<>();
        allMethods.addAll(methodConstants.keySet());
        allMethods.addAll(methodExceptions.keySet());
        allMethods.addAll(methodUrls.keySet());

        for (String method : allMethods) {
            ChunkEntity chunk = chunkMap.get(method);
            if (chunk == null) continue;
            String shortClass = chunk.getClassName() != null ? shortName(chunk.getClassName()) + ".java" : null;

            // 常量 JSON：[{value, resolvedValue, line, code, file}] —— 只有静态常量/枚举引用（带解析后的实际值）
            Set<String> consts = methodConstants.get(method);
            if (consts != null && !consts.isEmpty()) {
                ArrayNode arr = jsonMapper.createArrayNode();
                int n = 0;
                for (String v : consts) {
                    if (n >= 30) break;
                    ObjectNode o = jsonMapper.createObjectNode();
                    o.put("value", v);
                    // 解析枚举实际值（如 CommonApiCodeEnum.PARAM_CHECK_ERROR → 40001, 参数校验失败）
                    java.util.TreeMap<Integer, String> eArgs = enumArgs.get(v);
                    if (eArgs != null && !eArgs.isEmpty()) {
                        StringBuilder resolved = new StringBuilder();
                        for (String ev : eArgs.values()) {
                            if (resolved.length() > 0) resolved.append(", ");
                            resolved.append(ev);
                        }
                        o.put("resolvedValue", resolved.toString());
                    }
                    int[] cite = findConstantCitation(repoPath, method, chunk.getStartLine(), chunk.getEndLine(), v, srcCache);
                    if (cite != null) {
                        o.put("line", cite[0]);
                        o.put("code", srcLine(srcCache, method, repoPath, cite[0]));
                    }
                    if (shortClass != null) o.put("file", shortClass);
                    arr.add(o);
                    n++;
                }
                chunk.setConstants(arr.size() > 0 ? arr.toString() : null);
            } else {
                chunk.setConstants(null);
            }

            // 异常 JSON：[{kind, type, line, code}]
            List<String[]> excs = methodExceptions.get(method);
            if (excs != null && !excs.isEmpty()) {
                ArrayNode arr = jsonMapper.createArrayNode();
                Set<String> dedup = new HashSet<>();
                int n = 0;
                for (String[] ex : excs) {
                    if (n++ >= 20) break;
                    int lineNo = parseIntSafe(ex[2]) != null ? parseIntSafe(ex[2]) : 0;
                    String dk = ex[0] + ex[1] + lineNo;
                    if (!dedup.add(dk)) { n--; continue; }
                    ObjectNode o = jsonMapper.createObjectNode();
                    o.put("kind", ex[0]);
                    o.put("type", ex[1]);
                    if (lineNo > 0) {
                        o.put("line", lineNo);
                        String code = srcLine(srcCache, method, repoPath, lineNo);
                        if (code != null) o.put("code", code);
                    }
                    if (shortClass != null) o.put("file", shortClass);
                    arr.add(o);
                }
                chunk.setExceptions(arr.toString());
            } else {
                chunk.setExceptions(null);
            }

            // URL JSON：[{url, configKey, field}]
            List<String[]> urls = methodUrls.get(method);
            if (urls != null && !urls.isEmpty()) {
                ArrayNode arr = jsonMapper.createArrayNode();
                int n = 0;
                for (String[] u : urls) {
                    if (n++ >= 20) break;
                    ObjectNode o = jsonMapper.createObjectNode();
                    o.put("url", u[0]);
                    if (u[1] != null) o.put("configKey", u[1]);
                    if (u[2] != null) o.put("field", u[2]);
                    arr.add(o);
                }
                chunk.setResolvedUrls(arr.toString());
            } else {
                chunk.setResolvedUrls(null);
            }

            // 业务错误码+消息 JSON：[{code, msg, line, codeText, file}]
            List<String[]> errs = extractErrorCodes(repoPath, method, chunk.getStartLine(), chunk.getEndLine(), srcCache, enumArgs);
            if (!errs.isEmpty()) {
                ArrayNode arr = jsonMapper.createArrayNode();
                int n = 0;
                for (String[] e : errs) {
                    if (n++ >= 30) break;
                    ObjectNode o = jsonMapper.createObjectNode();
                    if (e[0] != null) o.put("code", e[0]);
                    if (e[1] != null) o.put("msg", e[1]);
                    if (e[2] != null) o.put("line", parseIntSafe(e[2]));
                    if (e[3] != null) o.put("codeText", e[3]);
                    if (shortClass != null) o.put("file", shortClass);
                    arr.add(o);
                }
                chunk.setErrorCodes(arr.toString());
            } else {
                chunk.setErrorCodes(null);
            }

            batch.add(chunk);
            if (batch.size() >= 500) {
                chunkRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) chunkRepo.saveAll(batch);
        logger.info("提取结构化数据(带源码引用): 常量方法={}, 异常方法={}, URL方法={}",
                methodConstants.size(), methodExceptions.size(), methodUrls.size());
        buildLogService.append(repoId, "提取结构化数据: 常量 " + methodConstants.size()
                + " / 异常 " + methodExceptions.size() + " / URL " + methodUrls.size() + " 个方法");
    }

    /** 读取某方法所在源码文件的全部行（缓存） */
    private List<String> loadSourceLines(Map<String, List<String>> cache, String fullMethod, String repoPath) {
        String className = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(0, fullMethod.lastIndexOf(':')) : null;
        if (className == null) return null;
        String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        if (cache.containsKey(topLevel)) return cache.get(topLevel);
        List<String> lines = null;
        String relativePath = topLevel.replace('.', '/') + ".java";
        Path sourceFile = findSourceFileInRepo(Path.of(repoPath), relativePath);
        if (sourceFile != null) {
            try { lines = Files.readAllLines(sourceFile); } catch (IOException ignored) {}
        }
        cache.put(topLevel, lines);
        return lines;
    }

    /** 取指定行的源码（去首尾空白），行号从 1 开始 */
    private String srcLine(Map<String, List<String>> cache, String fullMethod, String repoPath, int lineNo) {
        List<String> lines = loadSourceLines(cache, fullMethod, repoPath);
        if (lines == null || lineNo < 1 || lineNo > lines.size()) return null;
        return lines.get(lineNo - 1).trim();
    }

    /** 在方法源码范围内查找包含常量值的行，返回 [行号]；找不到返回 null */
    private int[] findConstantCitation(String repoPath, String fullMethod, Integer startLine, Integer endLine,
                                       String value, Map<String, List<String>> cache) {
        List<String> lines = loadSourceLines(cache, fullMethod, repoPath);
        if (lines == null) return null;
        int from = (startLine != null && startLine > 0) ? startLine : 1;
        int to = (endLine != null && endLine > 0 && endLine <= lines.size()) ? endLine : lines.size();
        // 枚举/静态常量 A.B 只搜后半部分常量名；字符串字面量直接搜
        String needle = value;
        int dot = value.lastIndexOf('.');
        boolean enumConst = dot > 0 && Character.isUpperCase(value.charAt(0)) && value.indexOf(' ') < 0;
        if (enumConst) needle = value.substring(dot + 1);
        for (int i = from - 1; i < to && i < lines.size(); i++) {
            if (i < 0) continue;
            if (lines.get(i).contains(needle)) return new int[]{i + 1};
        }
        return null;
    }

    /**
     * 从方法源码范围内提取业务错误码+消息：识别 Result.buildResult/throw new XxxException/.error(...) 等错误返回行，
     * 提取枚举引用（用 enumArgs 还原 code+枚举msg）与字符串字面量 msg。
     * 返回 [code展示, msg, 行号, 源码行]
     */
    private List<String[]> extractErrorCodes(String repoPath, String fullMethod, Integer startLine, Integer endLine,
                                             Map<String, List<String>> srcCache,
                                             Map<String, java.util.TreeMap<Integer, String>> enumArgs) {
        List<String[]> result = new ArrayList<>();
        List<String> lines = loadSourceLines(srcCache, fullMethod, repoPath);
        if (lines == null) return result;
        int from = (startLine != null && startLine > 0) ? startLine : 1;
        int to = (endLine != null && endLine > 0 && endLine <= lines.size()) ? endLine : lines.size();
        java.util.regex.Pattern enumRe = java.util.regex.Pattern.compile("([A-Z]\\w*(?:Enum|Code|Status|Error))\\.([A-Z][A-Z0-9_]{1,})");
        java.util.regex.Pattern strRe = java.util.regex.Pattern.compile("\"([^\"]{1,100})\"");
        Set<String> dedup = new HashSet<>();
        for (int i = from - 1; i < to && i < lines.size(); i++) {
            if (i < 0) continue;
            String line = lines.get(i);
            boolean errLine = line.contains("throw ") || line.contains("buildResult")
                    || line.contains(".error(") || line.contains(".fail(") || line.contains(".failed(")
                    || line.matches(".*new\\s+\\w*(Exception|Error)\\s*\\(.*")
                    || (line.contains("Result.") && (line.contains("Code") || line.contains("Error")));
            if (!errLine) continue;

            String codeDisplay = null, enumMsg = null;
            java.util.regex.Matcher em = enumRe.matcher(line);
            if (em.find()) {
                String ref = em.group(1) + "." + em.group(2);
                java.util.TreeMap<Integer, String> args = enumArgs.get(ref);
                if (args != null && !args.isEmpty()) {
                    for (String v : args.values()) {
                        if (codeDisplay == null && v != null && v.matches("-?\\d+")) codeDisplay = v;
                    }
                    for (String v : args.values()) {
                        if (v != null && !v.matches("-?\\d+") && !v.isBlank()) { enumMsg = v; break; }
                    }
                }
                if (codeDisplay == null) codeDisplay = ref;
                else codeDisplay = codeDisplay + " (" + ref + ")";
            }
            String litMsg = null;
            java.util.regex.Matcher sm = strRe.matcher(line);
            while (sm.find()) {
                String s = sm.group(1);
                if (!s.contains("{}") && s.length() >= 2) { litMsg = s; break; }
            }
            String msg = litMsg != null ? litMsg : enumMsg;
            if (codeDisplay == null && msg == null) continue;
            String dk = codeDisplay + "|" + msg;
            if (!dedup.add(dk)) continue;
            result.add(new String[]{codeDisplay, msg, String.valueOf(i + 1), line.trim()});
        }
        return result;
    }

    /**
     * 判断常量是否有业务/逻辑意义（而非日志模板/格式符/框架噪音）。
     * 保留：枚举引用(A.B)、业务参数key(驼峰/下划线单词)、URL/路径、Redis key 前缀、错误消息(含中文或 is empty 等)、超时常量(TimeUnit.X)
     * 过滤：纯数字、单字符、纯符号、日志模板({})、call/response/request 打印文本、含换行/制表、过短
     */
    private boolean isBusinessConstant(String v) {
        if (v == null) return false;
        int len = v.length();
        // 太短（<=2字符）或太长（>100）
        if (len <= 2 || len > 100) return false;
        // 纯数字
        if (v.matches("^-?\\d+(\\.\\d+)?$")) return false;
        // 含日志占位符 {}
        if (v.contains("{}")) return false;
        // 纯符号/空白
        if (v.matches("^[\\W\\s_]+$")) return false;
        // 日志/调试文本特征：以 "call "/"调用"/"response"/"request" 开头的描述性日志
        String lower = v.toLowerCase();
        if (lower.startsWith("call ") || lower.startsWith("调用") || lower.startsWith("response")
                || lower.matches("^(debug|info|warn|error|trace)\\b.*")) return false;
        // 枚举引用 A.B（首字母大写.全大写）→ 保留
        if (v.matches("^[A-Z]\\w+\\.[A-Z][A-Z0-9_]+$")) return true;
        // URL/路径
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("/api/")) return true;
        // Redis/缓存 key 前缀（含冒号分隔）
        if (v.contains(":") && !v.contains(" ") && v.length() >= 4) return true;
        // 业务参数 key（纯驼峰/下划线单词，无空格，>=3字符）
        if (v.matches("^[a-zA-Z][a-zA-Z0-9_]*$") && len >= 3 && len <= 40) return true;
        // 含中文 → 通常是业务错误消息
        if (v.matches(".*[\\u4e00-\\u9fff].*")) return true;
        // 含 "is empty"/"not found"/"invalid" 等错误消息关键词
        if (lower.contains("is empty") || lower.contains("not found") || lower.contains("invalid")
                || lower.contains("error") || lower.contains("failed")) return true;
        // 其余：过滤
        return false;
    }

    /** 取短类名：a.b.C → C */
    private String shortName(String fqcn) {
        if (fqcn == null) return "";
        return fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
    }

    /** 从 @Value 属性值中提取配置 key：${http.x.url} → http.x.url；${k:default} → k；非占位符返回 null */
    private String extractPlaceholderKey(String attrValue) {
        if (attrValue == null) return null;
        int start = attrValue.indexOf("${");
        if (start < 0) return null;
        int end = attrValue.indexOf('}', start);
        if (end < 0) return null;
        String inner = attrValue.substring(start + 2, end);
        int colon = inner.indexOf(':');
        return colon >= 0 ? inner.substring(0, colon) : inner;
    }

    /** 将集合用换行拼接，限制条数与总长度 */
    private String joinLimited(Set<String> values, int maxCount, int maxLen) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String v : values) {
            if (count >= maxCount) break;
            if (sb.length() + v.length() > maxLen) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(v);
            count++;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 从多个数据源充实 call_summary，作为方法的全文搜索索引
     * 数据源：字符串常量、异常类型、URL路径、字段名、源码注释
     */
    private void enrichCallSummary(Long repoId, String outputDir, String repoPath) {
        Map<String, ChunkEntity> chunkMap = new HashMap<>();
        chunkRepo.findByRepoId(repoId).forEach(c -> chunkMap.put(c.getFullMethod(), c));

        // 按类名建索引：类名 → 该类的所有方法 fullMethod 列表
        Map<String, List<String>> classMethods = new HashMap<>();
        for (String fullMethod : chunkMap.keySet()) {
            int colonIdx = fullMethod.lastIndexOf(':');
            if (colonIdx > 0) {
                String className = fullMethod.substring(0, colonIdx);
                classMethods.computeIfAbsent(className, k -> new ArrayList<>()).add(fullMethod);
            }
        }
        // 按参数类名建索引：参数类名 → 使用该参数的方法 fullMethod 列表
        Map<String, List<String>> paramClassMethods = new HashMap<>();
        for (String fullMethod : chunkMap.keySet()) {
            int parenStart = fullMethod.indexOf('(');
            int parenEnd = fullMethod.indexOf(')');
            if (parenStart > 0 && parenEnd > parenStart) {
                String params = fullMethod.substring(parenStart + 1, parenEnd);
                for (String param : params.split(",")) {
                    String trimmed = param.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("java.") && !trimmed.startsWith("javax.")) {
                        paramClassMethods.computeIfAbsent(trimmed, k -> new ArrayList<>()).add(fullMethod);
                    }
                }
            }
        }

        // 每个方法的额外关键词
        Map<String, Set<String>> methodKeywords = new HashMap<>();

        // 1. method_call_info 中的字符串常量值（type=v, valueType=java.lang.String）
        for (String line : readTsvFile(outputDir, "method_call_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 11) continue;
            String type = cols[3];  // v=常量值
            if (!"v".equals(type)) continue;
            String valueType = cols[8];
            String value = cols[9];
            String callerMethod = cols[10];
            if (value == null || value.isBlank()) continue;
            // 只取字符串常量，过滤太长的（JSON等）和太短的
            if ("java.lang.String".equals(valueType) && value.length() >= 2 && value.length() <= 100
                    && !value.startsWith("{") && !value.startsWith("[")) {
                methodKeywords.computeIfAbsent(callerMethod, k -> new LinkedHashSet<>()).add(value);
            }
        }

        // 2. method_throw 中的异常类型（用短类名）
        for (String line : readTsvFile(outputDir, "method_throw")) {
            String[] cols = line.split("\t");
            if (cols.length < 6) continue;
            String callerMethod = cols[0];
            String exceptionType = cols[5];
            if (exceptionType != null && !exceptionType.isBlank()) {
                String shortEx = exceptionType.contains(".") ? exceptionType.substring(exceptionType.lastIndexOf('.') + 1) : exceptionType;
                methodKeywords.computeIfAbsent(callerMethod, k -> new LinkedHashSet<>()).add(shortEx);
            }
        }

        // 3. api_endpoints 的 URL 路径
        for (ApiEndpointEntity ep : apiEndpointRepo.findByRepoId(repoId)) {
            if (ep.getUrlPath() != null && !ep.getUrlPath().isBlank()) {
                Set<String> kws = methodKeywords.computeIfAbsent(ep.getFullMethod(), k -> new LinkedHashSet<>());
                kws.add(ep.getUrlPath());
                // URL 拆分段（/word/stu/answer → word, stu, answer）
                for (String seg : ep.getUrlPath().split("/")) {
                    if (seg.length() >= 2) kws.add(seg);
                }
            }
        }

        // 4. field_annotation — DTO 字段上的 @Schema(description=), @NotEmpty(message=) 等业务描述
        //    格式: className | fieldName | annotationClass | attrName | attrValue
        Map<String, Set<String>> classKeywords = new HashMap<>();
        for (String line : readTsvFile(outputDir, "field_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 5) continue;
            String className = cols[0];
            String attrValue = cols[4];
            if (attrValue != null && !attrValue.isBlank()
                    && !attrValue.equals("true") && !attrValue.equals("false")
                    && !attrValue.startsWith("org.") && !attrValue.startsWith("java.")
                    && !attrValue.startsWith("javax.") && !attrValue.startsWith("jakarta.")
                    && attrValue.length() >= 2 && attrValue.length() <= 200) {
                classKeywords.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(attrValue);
            }
        }
        // 将 DTO 类的关键词关联到使用该 DTO 作为参数的方法
        for (Map.Entry<String, Set<String>> ce : classKeywords.entrySet()) {
            String dtoClass = ce.getKey();
            List<String> methods = paramClassMethods.get(dtoClass);
            if (methods != null) {
                for (String m : methods) {
                    methodKeywords.computeIfAbsent(m, k -> new LinkedHashSet<>()).addAll(ce.getValue());
                }
            }
        }

        // 5. enum_init_assign_info — 枚举常量的中文描述值
        //    格式: enumClass:constructor | constName | ordinal | argSeq | valueType | arrayDim | value
        Map<String, Set<String>> enumKeywords = new HashMap<>();
        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;
            String enumMethod = cols[0];
            String constName = cols[1];
            String value = cols[6];
            String enumClass = enumMethod.contains(":") ? enumMethod.substring(0, enumMethod.lastIndexOf(':')) : enumMethod;
            if (value != null && !value.isBlank() && value.length() >= 2 && value.length() <= 100) {
                enumKeywords.computeIfAbsent(enumClass, k -> new LinkedHashSet<>()).add(constName + " " + value);
            }
        }
        // 通过 field_usage_other 将枚举关键词关联到使用该枚举的方法
        //    格式: callerMethod | ... | enumClass | constName | ...
        if (!enumKeywords.isEmpty()) {
            for (String line : readTsvFile(outputDir, "field_usage_other")) {
                String[] cols = line.split("\t");
                if (cols.length < 5) continue;
                String callerMethod = cols[0];
                String usedClass = cols[4];
                Set<String> ekws = enumKeywords.get(usedClass);
                if (ekws != null) {
                    methodKeywords.computeIfAbsent(callerMethod, k -> new LinkedHashSet<>()).addAll(ekws);
                }
            }
        }

        // 6. 驼峰拆分方法名和类名（WordStuController → Word Stu Controller）
        for (Map.Entry<String, ChunkEntity> me : chunkMap.entrySet()) {
            ChunkEntity chunk = me.getValue();
            Set<String> kws = methodKeywords.computeIfAbsent(me.getKey(), k -> new LinkedHashSet<>());
            if (chunk.getClassName() != null) {
                String shortClass = chunk.getClassName().contains(".")
                        ? chunk.getClassName().substring(chunk.getClassName().lastIndexOf('.') + 1) : chunk.getClassName();
                kws.add(splitCamelCase(shortClass));
            }
            if (chunk.getMethodName() != null) {
                kws.add(splitCamelCase(chunk.getMethodName()));
            }
        }

        // 7. Spring Bean 名称关联到对应的方法
        //    格式: beanName | ? | beanClass | ? | ? | annotationClass | definingClass
        for (String line : readTsvFile(outputDir, "spring_bean")) {
            String[] cols = line.split("\t");
            if (cols.length < 3) continue;
            String beanName = cols[0];
            String beanClass = cols[2];
            if (beanName != null && !beanName.isBlank() && beanName.length() >= 2) {
                List<String> methods = classMethods.get(beanClass);
                if (methods != null) {
                    for (String m : methods) {
                        methodKeywords.computeIfAbsent(m, k -> new LinkedHashSet<>()).add(beanName);
                    }
                }
            }
        }

        // 8. 继承/实现关系：子类关联父类/接口名，父类关联子类名
        //    格式: childClass | ? | e/i | parentClass
        Map<String, Set<String>> classRelations = new HashMap<>();
        for (String line : readTsvFile(outputDir, "extends_impl")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            String childClass = cols[0];
            String parentClass = cols[3];
            // 跳过 JDK/框架类
            if (parentClass.startsWith("java.") || parentClass.startsWith("javax.")
                    || parentClass.startsWith("org.springframework.") || parentClass.startsWith("org.apache.")) continue;
            String shortChild = childClass.contains(".") ? childClass.substring(childClass.lastIndexOf('.') + 1) : childClass;
            String shortParent = parentClass.contains(".") ? parentClass.substring(parentClass.lastIndexOf('.') + 1) : parentClass;
            classRelations.computeIfAbsent(childClass, k -> new LinkedHashSet<>()).add(shortParent);
            classRelations.computeIfAbsent(parentClass, k -> new LinkedHashSet<>()).add(shortChild);
        }
        for (Map.Entry<String, Set<String>> cr : classRelations.entrySet()) {
            List<String> methods = classMethods.get(cr.getKey());
            if (methods != null) {
                for (String m : methods) {
                    methodKeywords.computeIfAbsent(m, k -> new LinkedHashSet<>()).addAll(cr.getValue());
                }
            }
        }

        // 9. 方法中使用的静态字段（枚举常量名）
        //    格式: callId | ? | ? | fieldClass | fieldName | fieldType | callerMethod | returnType
        for (String line : readTsvFile(outputDir, "method_call_static_field")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;
            String fieldName = cols[4];
            String callerMethod = cols[6];
            if (fieldName != null && !fieldName.isBlank() && fieldName.length() >= 2
                    && fieldName.equals(fieldName.toUpperCase())) { // 只取全大写的常量名
                methodKeywords.computeIfAbsent(callerMethod, k -> new LinkedHashSet<>()).add(fieldName);
            }
        }

        // 10. DTO 字段名（驼峰拆分）关联到使用该 DTO 的方法
        //     格式: className | fieldName | fieldType | ...
        Map<String, Set<String>> classFieldNames = new HashMap<>();
        for (String line : readTsvFile(outputDir, "field_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 2) continue;
            String className = cols[0];
            String fieldName = cols[1];
            if (fieldName != null && fieldName.length() >= 2
                    && !fieldName.equals("serialVersionUID") && !fieldName.startsWith("log")) {
                classFieldNames.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(splitCamelCase(fieldName));
            }
        }
        for (Map.Entry<String, Set<String>> cf : classFieldNames.entrySet()) {
            String dtoClass = cf.getKey();
            List<String> methods = paramClassMethods.get(dtoClass);
            if (methods != null) {
                for (String m : methods) {
                    methodKeywords.computeIfAbsent(m, k -> new LinkedHashSet<>()).addAll(cf.getValue());
                }
            }
        }

        // 11. 源码中的行内注释、Javadoc 和中文字符串
        extractSourceCommentsInto(repoId, repoPath, methodKeywords);

        // 12. 写入 call_summary
        List<ChunkEntity> batch = new ArrayList<>();
        int enriched = 0;
        for (Map.Entry<String, Set<String>> entry : methodKeywords.entrySet()) {
            ChunkEntity chunk = chunkMap.get(entry.getKey());
            if (chunk == null) continue;
            Set<String> kws = entry.getValue();
            if (kws.isEmpty()) continue;

            // 拼接关键词，限制总长度
            StringBuilder kwStr = new StringBuilder();
            for (String kw : kws) {
                if (kwStr.length() + kw.length() > 800) break;
                if (kwStr.length() > 0) kwStr.append(" ");
                kwStr.append(kw);
            }

            String existing = chunk.getCallSummary();
            String newPart = kwStr.toString();
            if (existing != null && !existing.isBlank()) {
                // 避免重复
                if (existing.contains(newPart)) continue;
                if (existing.length() > 1500) continue;
                chunk.setCallSummary(existing + " | " + newPart);
            } else {
                chunk.setCallSummary(newPart);
            }
            batch.add(chunk);
            enriched++;
            if (batch.size() >= 500) {
                chunkRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) chunkRepo.saveAll(batch);
        logger.info("充实 call_summary: {} 个方法", enriched);
        buildLogService.append(repoId, "充实搜索索引: " + enriched + " 个方法");
    }

    /**
     * 生成仓库画像：从分析数据中聚合出仓库级别的业务摘要
     * 用于多仓库场景下快速定位目标仓库
     */
    private void buildRepoProfile(Long repoId, String outputDir, RepositoryEntity repo) {
        Set<String> profileParts = new LinkedHashSet<>();

        // 1. 技术栈
        var projectInfo = projectInfoExtractor.extract(repo.getLocalPath());
        String techStack = projectInfoExtractor.generateTechStackDescription(projectInfo);
        if (!techStack.isBlank()) profileParts.add(techStack.replace("\n", " ").trim());

        // 2. 包名前缀（取最常见的前 3 层）
        Map<String, Integer> packageCount = new HashMap<>();
        for (ChunkEntity chunk : chunkRepo.findByRepoId(repoId)) {
            if (chunk.getPackageName() != null) {
                String[] parts = chunk.getPackageName().split("\\.");
                if (parts.length >= 3) {
                    String prefix = parts[0] + "." + parts[1] + "." + parts[2];
                    packageCount.merge(prefix, 1, Integer::sum);
                }
            }
        }
        packageCount.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .forEach(e -> profileParts.add("包:" + e.getKey()));

        // 3. URL 前缀聚合（/word, /sign, /interaction 等）
        Set<String> urlPrefixes = new LinkedHashSet<>();
        for (ApiEndpointEntity ep : apiEndpointRepo.findByRepoId(repoId)) {
            if (ep.getUrlPath() != null && ep.getUrlPath().startsWith("/")) {
                String[] segs = ep.getUrlPath().split("/");
                if (segs.length >= 2 && !segs[1].isEmpty()) {
                    urlPrefixes.add("/" + segs[1]);
                }
            }
        }
        if (!urlPrefixes.isEmpty()) {
            profileParts.add("URL模块:" + String.join(" ", urlPrefixes));
        }

        // 4. 类级别注解中文描述（@Tag(name=), @Api(value=) 等）
        Set<String> classDescs = new LinkedHashSet<>();
        for (String line : readTsvFile(outputDir, "class_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length >= 4) {
                String attrValue = cols[3];
                if (attrValue != null && attrValue.length() >= 2 && attrValue.length() <= 50
                        && containsChinese(attrValue)) {
                    classDescs.add(attrValue);
                }
            }
        }
        if (!classDescs.isEmpty()) {
            profileParts.add("业务模块:" + String.join(" ", classDescs.stream().limit(30).collect(Collectors.toList())));
        }

        // 5. 枚举中文描述值（去重后取前 30 个）
        Set<String> enumDescs = new LinkedHashSet<>();
        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length >= 7) {
                String value = cols[6];
                if (value != null && value.length() >= 2 && value.length() <= 30
                        && containsChinese(value)) {
                    enumDescs.add(value);
                }
            }
        }
        if (!enumDescs.isEmpty()) {
            profileParts.add("业务术语:" + String.join(" ", enumDescs.stream().limit(30).collect(Collectors.toList())));
        }

        // 6. 异常类型（去重）
        Set<String> exTypes = new LinkedHashSet<>();
        for (String line : readTsvFile(outputDir, "method_throw")) {
            String[] cols = line.split("\t");
            if (cols.length >= 6 && cols[5] != null && !cols[5].isBlank()) {
                String shortEx = cols[5].contains(".") ? cols[5].substring(cols[5].lastIndexOf('.') + 1) : cols[5];
                if (!shortEx.equals("Throwable") && !shortEx.equals("Exception")) {
                    exTypes.add(shortEx);
                }
            }
        }
        if (!exTypes.isEmpty()) {
            profileParts.add("异常:" + String.join(" ", exTypes.stream().limit(10).collect(Collectors.toList())));
        }

        // 7. 方法注解中文描述（@Operation(summary=) 等，去重取前 30）
        Set<String> methodDescs = new LinkedHashSet<>();
        for (String line : readTsvFile(outputDir, "method_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length >= 6) {
                String attrValue = cols[5];
                if (attrValue != null && attrValue.length() >= 2 && attrValue.length() <= 50
                        && containsChinese(attrValue)) {
                    methodDescs.add(attrValue);
                }
            }
        }
        if (!methodDescs.isEmpty()) {
            profileParts.add("接口描述:" + String.join(" ", methodDescs.stream().limit(30).collect(Collectors.toList())));
        }

        // 8. 字段注解中文描述（@Schema(description=) 等，去重取前 20）
        Set<String> fieldDescs = new LinkedHashSet<>();
        for (String line : readTsvFile(outputDir, "field_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length >= 5) {
                String attrValue = cols[4];
                if (attrValue != null && attrValue.length() >= 2 && attrValue.length() <= 50
                        && containsChinese(attrValue)) {
                    fieldDescs.add(attrValue);
                }
            }
        }
        if (!fieldDescs.isEmpty()) {
            profileParts.add("字段描述:" + String.join(" ", fieldDescs.stream().limit(20).collect(Collectors.toList())));
        }

        // 拼接并截断
        String profile = String.join(" | ", profileParts);
        if (profile.length() > 3000) profile = profile.substring(0, 3000);

        repo.setProfile(profile);
        repositoryRepo.save(repo);
        logger.info("仓库画像生成完成: repoId={}, 长度={}", repoId, profile.length());
        buildLogService.append(repoId, "✅ 仓库画像生成完成 (" + profile.length() + " 字)");
    }

    /**
     * 生成项目概览文档（Markdown 格式）
     * 从分析数据中聚合生成，不调 AI
     */
    private void generateOverview(Long repoId, String outputDir, RepositoryEntity repo) {
        StringBuilder doc = new StringBuilder();
        List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);
        List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);

        // 1. 项目基本信息
        doc.append("# ").append(repo.getName()).append(" 项目概览\n\n");
        doc.append("| 项目 | 信息 |\n|------|------|\n");
        doc.append("| 仓库地址 | ").append(repo.getGitUrl()).append(" |\n");
        doc.append("| 分支 | ").append(repo.getBranch()).append(" |\n");
        if (repo.getLastCommitHash() != null) {
            doc.append("| 最新提交 | ").append(repo.getLastCommitHash().substring(0, Math.min(8, repo.getLastCommitHash().length()))).append(" |\n");
        }
        if (repo.getLastSyncTime() != null) {
            doc.append("| 分析时间 | ").append(repo.getLastSyncTime()).append(" |\n");
        }
        doc.append("\n");

        // 2. 技术栈
        var projectInfo = projectInfoExtractor.extract(repo.getLocalPath());
        doc.append("## 技术栈\n\n");
        doc.append("| 技术 | 版本 |\n|------|------|\n");
        if (projectInfo.buildTool != null) doc.append("| 构建工具 | ").append(projectInfo.buildTool).append(" |\n");
        if (projectInfo.javaVersion != null) doc.append("| Java | ").append(projectInfo.javaVersion).append(" |\n");
        if (projectInfo.springBootVersion != null) doc.append("| Spring Boot | ").append(projectInfo.springBootVersion).append(" |\n");
        for (var dep : projectInfo.dependencies.entrySet()) {
            String key = dep.getKey().toLowerCase();
            if (key.contains("mybatis") || key.contains("redis") || key.contains("kafka")
                    || key.contains("grpc") || key.contains("sentinel") || key.contains("nacos")
                    || key.contains("elasticsearch") || key.contains("mongodb") || key.contains("rabbitmq")
                    || key.contains("rocketmq") || key.contains("feign") || key.contains("dubbo")) {
                String name = dep.getKey().contains(":") ? dep.getKey().substring(dep.getKey().indexOf(':') + 1) : dep.getKey();
                String version = dep.getValue();
                if (version != null && !version.isBlank() && !version.startsWith("${")) {
                    doc.append("| ").append(name).append(" | ").append(version).append(" |\n");
                }
            }
        }
        doc.append("\n");

        // 3. 接口列表（按类型分组）
        if (!endpoints.isEmpty()) {
            Map<String, List<ApiEndpointEntity>> byType = new LinkedHashMap<>();
            for (ApiEndpointEntity ep : endpoints) {
                byType.computeIfAbsent(ep.getEndpointType(), k -> new ArrayList<>()).add(ep);
            }
            doc.append("## 接口列表（共 ").append(endpoints.size()).append(" 个）\n\n");
            for (var entry : byType.entrySet()) {
                doc.append("### ").append(entry.getKey()).append("（").append(entry.getValue().size()).append(" 个）\n\n");
                doc.append("| 方法 | URL/Topic | 类名 | 描述 |\n|------|----------|------|------|\n");
                for (ApiEndpointEntity ep : entry.getValue()) {
                    String shortClass = ep.getClassName() != null && ep.getClassName().contains(".")
                            ? ep.getClassName().substring(ep.getClassName().lastIndexOf('.') + 1) : (ep.getClassName() != null ? ep.getClassName() : "");
                    String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : "";
                    String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
                    String desc = "";
                    var chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, ep.getFullMethod());
                    if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                        for (String part : chunk.get().getCallSummary().split("\\|")) {
                            String trimmed = part.trim();
                            if (trimmed.length() >= 2 && trimmed.length() <= 30 && containsChinese(trimmed)) {
                                desc = trimmed;
                                break;
                            }
                        }
                    }
                    doc.append("| ").append(method).append(" | ").append(url).append(" | ").append(shortClass).append(" | ").append(desc).append(" |\n");
                }
                doc.append("\n");
            }
        }

        // 4. 枚举/状态码字典（按枚举类分组，含 code → 中文）
        Map<String, List<String[]>> enumsByClass = new LinkedHashMap<>();
        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length >= 7) {
                String enumMethod = cols[0];
                String constName = cols[1];
                String ordinal = cols[2];
                String value = cols[6];
                String enumClass = enumMethod.contains(":") ? enumMethod.substring(0, enumMethod.lastIndexOf(':')) : enumMethod;
                if (!hasSourceFile(repo.getLocalPath(), enumClass)) continue;
                String shortClass = enumClass.contains(".") ? enumClass.substring(enumClass.lastIndexOf('.') + 1) : enumClass;
                if (value != null && !value.isBlank() && value.length() >= 1 && value.length() <= 50) {
                    enumsByClass.computeIfAbsent(shortClass, k -> new ArrayList<>()).add(new String[]{constName, ordinal, value});
                }
            }
        }
        if (!enumsByClass.isEmpty()) {
            doc.append("## 枚举/状态码字典\n\n");
            for (var entry : enumsByClass.entrySet()) {
                doc.append("### ").append(entry.getKey()).append("\n\n");
                doc.append("| 常量名 | 序号 | 说明 |\n|--------|------|------|\n");
                Set<String> seen = new HashSet<>();
                for (String[] row : entry.getValue()) {
                    if (seen.add(row[0] + "|" + row[1])) {
                        doc.append("| ").append(row[0]).append(" | ").append(row[1]).append(" | ").append(row[2]).append(" |\n");
                    }
                }
                doc.append("\n");
            }
        }

        // 5. 核心业务流程（Mermaid 流程图）
        if (!endpoints.isEmpty()) {
            doc.append("## 核心业务流程\n\n");
            int flowCount = 0;
            for (ApiEndpointEntity ep : endpoints) {
                if (flowCount >= 10) break;
                String shortClass = ep.getClassName() != null && ep.getClassName().contains(".")
                        ? ep.getClassName().substring(ep.getClassName().lastIndexOf('.') + 1) : (ep.getClassName() != null ? ep.getClassName() : "");
                String methodName = ep.getFullMethod().contains(":") ? ep.getFullMethod().substring(ep.getFullMethod().lastIndexOf(':') + 1) : ep.getFullMethod();
                int paren = methodName.indexOf('(');
                if (paren > 0) methodName = methodName.substring(0, paren);
                String httpInfo = ep.getHttpMethod() != null ? ep.getHttpMethod() + " " : "";
                String urlInfo = ep.getUrlPath() != null ? ep.getUrlPath() : "";

                doc.append("### ").append(httpInfo).append(urlInfo).append("\n\n");

                try {
                    var tree = callGraphEngine.expandCallTree(repoId, ep.getFullMethod(), 6);
                    if (tree.root() != null) {
                        doc.append("```mermaid\nflowchart TD\n");
                        Set<String> visited = new HashSet<>();
                        renderMermaidFlow(doc, tree.root(), visited, repoId, repo.getLocalPath(), allBoundaries);
                        doc.append("```\n\n");
                    }
                } catch (Exception e) {
                    doc.append("> 调用链加载失败\n\n");
                }
                flowCount++;
            }
        }

        // 6. 异常处理汇总
        List<String> throwLines = readTsvFile(outputDir, "method_throw");
        if (!throwLines.isEmpty()) {
            Map<String, List<String>> throwByType = new LinkedHashMap<>();
            for (String line : throwLines) {
                String[] cols = line.split("\t");
                if (cols.length < 6) continue;
                String callerMethod = cols[0];
                String exType = cols[5];
                if (exType == null || exType.isBlank()) continue;
                if (!hasSourceFile(repo.getLocalPath(), callerMethod.contains(":") ? callerMethod.substring(0, callerMethod.lastIndexOf(':')) : callerMethod)) continue;
                String shortEx = exType.contains(".") ? exType.substring(exType.lastIndexOf('.') + 1) : exType;
                String shortMethod = callerMethod.contains(":") ? callerMethod.substring(callerMethod.lastIndexOf('.') + 1) : callerMethod;
                int p = shortMethod.indexOf('(');
                if (p > 0) shortMethod = shortMethod.substring(0, p);
                throwByType.computeIfAbsent(shortEx, k -> new ArrayList<>()).add(shortMethod);
            }
            if (!throwByType.isEmpty()) {
                doc.append("## 异常处理\n\n");
                doc.append("| 异常类型 | 数量 | 涉及方法 |\n|----------|------|----------|\n");
                for (var entry : throwByType.entrySet()) {
                    List<String> methods = entry.getValue().stream().distinct().collect(Collectors.toList());
                    String methodList = methods.size() > 3
                            ? String.join(", ", methods.subList(0, 3)) + " 等" + methods.size() + "个"
                            : String.join(", ", methods);
                    doc.append("| ").append(entry.getKey()).append(" | ").append(methods.size())
                            .append(" | ").append(methodList).append(" |\n");
                }
                doc.append("\n");
            }
        }

        // 7. 服务间调用关系（Mermaid 图）
        List<BoundaryEntity> externalCalls = allBoundaries.stream()
                .filter(b -> "HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType()) || "MQ".equals(b.getBoundaryType()))
                .collect(Collectors.toList());
        if (!externalCalls.isEmpty()) {
            doc.append("## 服务间调用关系\n\n");
            doc.append("```mermaid\ngraph LR\n");
            doc.append("    self[\"").append(repo.getName()).append("\"]\n");
            Set<String> seenEdges = new HashSet<>();
            int nodeId = 0;
            for (BoundaryEntity b : externalCalls) {
                String firstLine = b.getContext() != null ? b.getContext().split("\n")[0] : "";
                String edgeKey = b.getBoundaryType() + "|" + firstLine;
                if (!seenEdges.add(edgeKey)) continue;
                String label = switch (b.getBoundaryType()) {
                    case "HTTP" -> "HTTP";
                    case "GRPC" -> "gRPC";
                    case "MQ" -> "MQ";
                    default -> "";
                };
                String targetName = firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;
                targetName = targetName.replace("\"", "'");
                doc.append("    self -->|").append(label).append("| n").append(nodeId).append("[\"").append(targetName).append("\"]\n");
                nodeId++;
                if (nodeId >= 20) break;
            }
            doc.append("```\n\n");
        }

        // 8. 核心入参实体类
        doc.append("## 核心入参实体类\n\n");
        Set<String> dtoShown = new HashSet<>();
        for (ApiEndpointEntity ep : endpoints) {
            try {
                var detail = callGraphEngine.getMethodSourceDetail(repoId, ep.getFullMethod(), ep.getFullMethod());
                if (detail != null && detail.paramClasses() != null) {
                    for (var pc : detail.paramClasses()) {
                        if (dtoShown.add(pc.className()) && !pc.fields().isEmpty()) {
                            doc.append("### ").append(pc.shortName()).append("\n\n");
                            doc.append("| 字段 | 类型 | 必填 | 说明 |\n|------|------|------|------|\n");
                            for (String field : pc.fields()) {
                                boolean required = field.startsWith("* ");
                                String f = required ? field.substring(2).trim() : field.trim();
                                String fieldName = "", fieldType = "", comment = "";
                                if (f.contains(":")) {
                                    fieldName = f.substring(0, f.indexOf(':')).trim();
                                    String rest = f.substring(f.indexOf(':') + 1).trim();
                                    if (rest.contains("//")) {
                                        fieldType = rest.substring(0, rest.indexOf("//")).trim();
                                        comment = rest.substring(rest.indexOf("//") + 2).trim();
                                    } else {
                                        fieldType = rest;
                                    }
                                } else {
                                    fieldName = f;
                                }
                                doc.append("| ").append(fieldName).append(" | ").append(fieldType)
                                        .append(" | ").append(required ? "✓" : "").append(" | ").append(comment).append(" |\n");
                            }
                            doc.append("\n");
                        }
                    }
                }
            } catch (Exception e) { /* skip */ }
            if (dtoShown.size() >= 20) break;
        }

        // 9. 关键配置项（脱敏）
        var configs = repoConfigRepo.findByRepoId(repoId);
        if (configs != null && !configs.isEmpty()) {
            List<RepoConfigEntity> keyConfigs = configs.stream()
                    .filter(c -> c.getConfigValue() != null && !c.getConfigValue().isBlank())
                    .filter(c -> {
                        String k = c.getConfigKey().toLowerCase();
                        return k.contains("url") || k.contains("host") || k.contains("port")
                                || k.contains("topic") || k.contains("timeout") || k.contains("pool")
                                || k.contains("datasource") || k.contains("redis") || k.contains("kafka")
                                || k.contains("grpc") || k.contains("feign") || k.contains("sentinel")
                                || k.contains("server.port") || k.contains("spring.application");
                    })
                    .limit(30)
                    .collect(Collectors.toList());
            if (!keyConfigs.isEmpty()) {
                doc.append("## 关键配置项\n\n");
                doc.append("| 配置项 | 值 |\n|--------|-----|\n");
                for (var c : keyConfigs) {
                    String val = desensitize(c.getConfigKey(), c.getConfigValue());
                    if (val.length() > 80) val = val.substring(0, 80) + "...";
                    doc.append("| ").append(c.getConfigKey()).append(" | ").append(val).append(" |\n");
                }
                doc.append("\n");
            }
        }

        // 10. 统计
        int methodCount = chunkRepo.findByRepoId(repoId).size();
        int callCount = callGraphRepo.findByRepoId(repoId).size();
        doc.append("## 统计\n\n");
        doc.append("| 指标 | 数量 |\n|------|------|\n");
        doc.append("| 方法总数 | ").append(methodCount).append(" |\n");
        doc.append("| 调用关系 | ").append(callCount).append(" |\n");
        doc.append("| 接口数 | ").append(endpoints.size()).append(" |\n");
        doc.append("| 边界点 | ").append(allBoundaries.size()).append(" |\n");
        doc.append("\n");

        repo.setOverview(doc.toString());
        repositoryRepo.save(repo);
        logger.info("项目概览文档生成完成: repoId={}, 长度={}", repoId, doc.length());
        buildLogService.append(repoId, "✅ 项目概览文档生成完成 (" + doc.length() + " 字)");
    }

    /** 渲染 Mermaid 流程图节点 */
    private void renderMermaidFlow(StringBuilder doc, CallGraphEngine.CallTreeNodeDTO node,
                                    Set<String> visited, Long repoId, String repoPath,
                                    List<BoundaryEntity> allBoundaries) {
        if (node == null) return;
        String nodeId = "n" + Math.abs(node.fullMethod().hashCode());
        if (visited.contains(node.fullMethod())) return;
        visited.add(node.fullMethod());

        String shortName = shortClassName(node.className() != null ? node.className() : "") + "." + (node.methodName() != null ? node.methodName() : "");
        boolean hasSrc = hasSourceFile(repoPath, node.className());

        // 节点样式：有源码用方框，依赖库用圆角
        String boundaryTag = "";
        if (node.boundaries() != null && !node.boundaries().isEmpty()) {
            boundaryTag = " " + node.boundaries().stream().map(b -> "[" + b.boundaryType() + "]").distinct().collect(Collectors.joining(" "));
        }

        if (hasSrc) {
            doc.append("    ").append(nodeId).append("[\"").append(shortName).append(boundaryTag).append("\"]\n");
        } else {
            doc.append("    ").append(nodeId).append("(\"").append(shortName).append("\")\n");
        }

        if (node.children() != null) {
            for (var child : node.children()) {
                String childId = "n" + Math.abs(child.fullMethod().hashCode());
                doc.append("    ").append(nodeId).append(" --> ").append(childId).append("\n");
                renderMermaidFlow(doc, child, visited, repoId, repoPath, allBoundaries);
            }
        }
    }

    /** 配置值脱敏 */
    private String desensitize(String key, String value) {
        if (value == null || value.isBlank()) return "";
        String keyLower = key.toLowerCase();

        // 密码类：直接隐藏
        if (keyLower.contains("password") || keyLower.contains("secret")
                || keyLower.contains("token") || keyLower.contains("api-key")
                || keyLower.contains("apikey") || keyLower.contains("credential")) {
            return "******";
        }

        // 用户名类：部分隐藏
        if (keyLower.contains("username") || keyLower.contains("user-name")) {
            return value.length() > 2 ? value.substring(0, 2) + "***" : "***";
        }

        // URL 类：保留协议和路径，隐藏域名/IP
        if (keyLower.contains("url") || keyLower.contains("base-url") || keyLower.contains("endpoint")) {
            // 隐藏 // 之后到下一个 / 或 : 之间的主机部分
            value = value.replaceAll("//[^/:]+", "//***");
            return value.length() > 80 ? value.substring(0, 80) + "..." : value;
        }

        // host/server 类：隐藏域名和 IP
        if (keyLower.contains("host") || keyLower.contains("server") || keyLower.contains("broker") || keyLower.contains("bootstrap")) {
            // 多个地址逗号分隔
            return value.replaceAll("[\\w.-]+\\.(cn|com|net|org|io|local)(:\\d+)?", "***$2")
                        .replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "***");
        }

        // IP 地址通用脱敏
        value = value.replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "***");

        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }


    /** 渲染调用链流程图（文本树形结构） */
    private void renderFlowChart(StringBuilder doc, CallGraphEngine.CallTreeNodeDTO node,
                                  String prefix, boolean isLast, Set<String> visited,
                                  Long repoId, String repoPath) {
        if (node == null) return;
        if (visited.contains(node.fullMethod())) {
            String shortName = shortClassName(node.className() != null ? node.className() : "") + "." + (node.methodName() != null ? node.methodName() : "");
            doc.append(prefix).append(isLast ? "└── " : "├── ").append(shortName).append(" ↻ (循环)\n");
            return;
        }
        visited.add(node.fullMethod());

        String shortName = shortClassName(node.className() != null ? node.className() : "") + "." + (node.methodName() != null ? node.methodName() : "");
        // 只显示有源码的业务方法
        boolean hasSrc = hasSourceFile(repoPath, node.className());

        // 边界点标记
        StringBuilder tags = new StringBuilder();
        if (node.boundaries() != null) {
            for (var b : node.boundaries()) {
                tags.append(" [").append(b.boundaryType()).append("]");
            }
        }

        String connector = isLast ? "└── " : "├── ";
        if (hasSrc) {
            doc.append(prefix).append(connector).append(shortName).append(tags).append("\n");
        } else {
            // 依赖库方法用斜体标记
            doc.append(prefix).append(connector).append("(").append(shortName).append(")").append(tags).append("\n");
        }

        if (node.children() != null) {
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            for (int i = 0; i < node.children().size(); i++) {
                renderFlowChart(doc, node.children().get(i), childPrefix,
                        i == node.children().size() - 1, visited, repoId, repoPath);
            }
        }
    }

    private boolean containsChinese(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    /**
     * 从源码提取注释到 methodKeywords map 中
     */
    private void extractSourceCommentsInto(Long repoId, String repoPath, Map<String, Set<String>> methodKeywords) {
        List<ChunkEntity> chunks = chunkRepo.findByRepoId(repoId);
        Map<String, List<ChunkEntity>> byClass = new LinkedHashMap<>();
        for (ChunkEntity chunk : chunks) {
            if (chunk.getClassName() != null) {
                byClass.computeIfAbsent(chunk.getClassName(), k -> new ArrayList<>()).add(chunk);
            }
        }

        for (Map.Entry<String, List<ChunkEntity>> entry : byClass.entrySet()) {
            String className = entry.getKey();
            String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            String relativePath = topLevel.replace('.', '/') + ".java";
            Path sourceFile = findSourceFileInRepo(Path.of(repoPath), relativePath);
            if (sourceFile == null) continue;

            try {
                List<String> lines = Files.readAllLines(sourceFile);
                String classComment = extractClassComment(lines);

                for (ChunkEntity chunk : entry.getValue()) {
                    Set<String> kws = methodKeywords.computeIfAbsent(chunk.getFullMethod(), k -> new LinkedHashSet<>());
                    if (classComment != null && !classComment.isBlank()) kws.add(classComment);

                    if (chunk.getStartLine() != null && chunk.getStartLine() > 0) {
                        String methodComment = extractMethodComment(lines, chunk.getStartLine());
                        if (methodComment != null && !methodComment.isBlank()) kws.add(methodComment);
                        // 提取方法体内的行内注释
                        List<String> inlineComments = extractInlineComments(lines, chunk.getStartLine(), chunk.getEndLine());
                        kws.addAll(inlineComments);
                    } else {
                        String methodComment = searchMethodComment(lines, chunk.getMethodName());
                        if (methodComment != null && !methodComment.isBlank()) kws.add(methodComment);
                    }
                }
            } catch (IOException e) {
                logger.debug("读取源码失败: {}", sourceFile);
            }
        }
    }

    /** 驼峰拆分：WordStuController → word stu controller */
    private String splitCamelCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1 $2")
                   .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                   .toLowerCase();
    }

    /** 提取方法体内的行内注释和中文字符串（// xxx、日志消息、异常消息、常量等） */
    private List<String> extractInlineComments(List<String> lines, int startLine, Integer endLine) {
        List<String> comments = new ArrayList<>();
        int end = endLine != null ? Math.min(endLine, lines.size()) : Math.min(startLine + 100, lines.size());
        for (int i = startLine; i < end; i++) {
            String trimmed = lines.get(i).trim();
            // 行内注释: // xxx
            int idx = trimmed.indexOf("//");
            if (idx >= 0) {
                String comment = trimmed.substring(idx + 2).trim();
                if (comment.length() >= 2 && comment.length() <= 100
                        && !comment.startsWith("noinspection") && !comment.startsWith("TODO")
                        && !comment.startsWith("FIXME") && !comment.startsWith("NOSONAR")) {
                    comments.add(comment);
                }
            }
            // 提取中文字符串（双引号内含中文的内容）
            java.util.regex.Matcher chMatcher = CHINESE_STRING_PATTERN.matcher(trimmed);
            while (chMatcher.find()) {
                String chStr = chMatcher.group(1) != null ? chMatcher.group(1) : chMatcher.group(2);
                if (chStr != null && chStr.length() >= 2 && chStr.length() <= 80) {
                    comments.add(chStr);
                }
            }
        }
        return comments;
    }

    /** 提取类级别注释（package 声明到 class 声明之间的 Javadoc 和注解描述） */
    private String extractClassComment(List<String> lines) {
        StringBuilder comment = new StringBuilder();
        boolean inJavadoc = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过 package 和 import
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue;

            // Javadoc
            if (trimmed.startsWith("/**")) {
                inJavadoc = true;
                String content = trimmed.replace("/**", "").replace("*/", "").replace("*", "").trim();
                if (!content.isEmpty()) comment.append(content).append(" ");
                continue;
            }
            if (inJavadoc) {
                if (trimmed.contains("*/")) {
                    inJavadoc = false;
                    String content = trimmed.replace("*/", "").replace("*", "").trim();
                    if (!content.isEmpty() && !content.startsWith("@")) comment.append(content).append(" ");
                } else {
                    String content = trimmed.replace("*", "").trim();
                    if (!content.isEmpty() && !content.startsWith("@")) comment.append(content).append(" ");
                }
                continue;
            }

            // 单行注释
            if (trimmed.startsWith("//")) {
                String content = trimmed.substring(2).trim();
                if (!content.isEmpty()) comment.append(content).append(" ");
                continue;
            }

            // 到了 class 声明就停止
            if (trimmed.contains("class ") || trimmed.contains("interface ") || trimmed.contains("enum ")) {
                break;
            }
        }

        String result = comment.toString().trim();
        return result.length() > 500 ? result.substring(0, 500) : result;
    }

    /** 提取方法上方的注释（从 startLine 向上找 Javadoc 和注释） */
    private String extractMethodComment(List<String> lines, int startLine) {
        StringBuilder comment = new StringBuilder();

        // 第一部分：方法上方的注释（向上找）
        int searchStart = Math.max(0, startLine - 15);
        int searchEnd = Math.min(lines.size(), startLine);

        List<String> aboveComments = new ArrayList<>();
        for (int i = searchEnd - 1; i >= searchStart; i--) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("@")) continue;
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/**") || trimmed.endsWith("*/")) {
                String content = trimmed.replace("/**", "").replace("*/", "").replace("*", "").replace("//", "").trim();
                if (!content.isEmpty() && !content.startsWith("@param") && !content.startsWith("@return")
                        && !content.startsWith("@throws") && !content.startsWith("@see")) {
                    aboveComments.add(0, content);
                }
                continue;
            }
            break;
        }
        if (!aboveComments.isEmpty()) {
            comment.append(String.join(" ", aboveComments));
        }

        // 第二部分：方法体内部的注释（向下找，直到方法结束）
        int braceDepth = 0;
        boolean inMethod = false;
        int endLine = Math.min(lines.size(), startLine + 200); // 最多看 200 行
        for (int i = startLine - 1; i < endLine; i++) {
            String trimmed = lines.get(i).trim();
            braceDepth += countChar(trimmed, '{') - countChar(trimmed, '}');
            if (trimmed.contains("{")) inMethod = true;
            if (inMethod && braceDepth <= 0) break; // 方法结束

            // 提取行内注释
            if (trimmed.startsWith("//")) {
                String content = trimmed.substring(2).trim();
                if (!content.isEmpty() && content.length() > 2) {
                    comment.append(" ").append(content);
                }
            } else if (trimmed.startsWith("/*") && !trimmed.startsWith("/**")) {
                String content = trimmed.replace("/*", "").replace("*/", "").trim();
                if (!content.isEmpty()) comment.append(" ").append(content);
            }
            // 行尾注释
            int lineCommentIdx = trimmed.indexOf("//");
            if (lineCommentIdx > 0 && !trimmed.startsWith("//") && !trimmed.substring(0, lineCommentIdx).contains("\"//")) {
                String content = trimmed.substring(lineCommentIdx + 2).trim();
                if (!content.isEmpty() && content.length() > 2) {
                    comment.append(" ").append(content);
                }
            }

            // 提取中文字符串（日志、异常消息、常量等）
            java.util.regex.Matcher chMatcher = CHINESE_STRING_PATTERN.matcher(trimmed);
            while (chMatcher.find()) {
                String chStr = chMatcher.group(1) != null ? chMatcher.group(1) : chMatcher.group(2);
                if (chStr != null && chStr.length() >= 2 && chStr.length() <= 50) {
                    comment.append(" ").append(chStr);
                }
            }
        }

        String result = comment.toString().trim();
        return result.length() > 500 ? result.substring(0, 500) : result;
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) if (ch == c) count++;
        return count;
    }

    /** 用方法名在源码中搜索注释（没有行号时的兜底方案） */
    private String searchMethodComment(List<String> lines, String methodName) {
        if (methodName == null) return null;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            // 找到方法声明行
            if (trimmed.contains(methodName + "(") && (trimmed.contains("public ") || trimmed.contains("private ")
                    || trimmed.contains("protected "))) {
                return extractMethodComment(lines, i + 1);
            }
        }
        return null;
    }

    private int parseMethodCall(Long repoId, String outputDir) {
        // method_call: 序号 | 启用 | 调用方 | (类型)被调用方 | 行号 | 返回类型 | 数组维度 | 对象类型 | 原始返回 | 实际返回 | 调用jar | 被调用jar | 描述
        int count = 0;
        List<CallGraphEntity> batch = new ArrayList<>();
        for (String line : readTsvFile(outputDir, "method_call")) {
            String[] cols = line.split("\t");
            if (cols.length < 12) continue;

            CallGraphEntity cg = new CallGraphEntity();
            cg.setRepoId(repoId);
            cg.setCallId(parseIntSafe(cols[0]));
            cg.setEnabled("1".equals(cols[1]));
            cg.setCallerMethod(cols[2]);

            // 解析 "(调用类型)被调用方法"
            String calleeWithType = cols[3];
            String callType = "";
            String calleeMethod = calleeWithType;
            if (calleeWithType.startsWith("(") && calleeWithType.contains(")")) {
                int endParen = calleeWithType.indexOf(')');
                callType = calleeWithType.substring(1, endParen);
                calleeMethod = calleeWithType.substring(endParen + 1);
            }
            cg.setCallType(callType);
            cg.setCalleeMethod(calleeMethod);

            cg.setLineNumber(parseIntSafe(cols[4]));
            cg.setCallerReturnType(cols[5]);
            cg.setCalleeObjType(cols.length > 7 ? cols[7] : null);
            cg.setCalleeRawReturnType(cols.length > 8 ? cols[8] : null);
            cg.setCalleeActualReturnType(cols.length > 9 ? cols[9] : null);
            cg.setCallerJarNum(cols.length > 10 ? parseIntSafe(cols[10]) : null);
            cg.setCalleeJarNum(cols.length > 11 ? parseIntSafe(cols[11]) : null);

            batch.add(cg);
            count++;

            if (batch.size() >= 500) {
                callGraphRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            callGraphRepo.saveAll(batch);
        }
        logger.info("解析 method_call: {} 条", count);
        return count;
    }

    private void parseExtendsImpl(Long repoId, String outputDir) {
        // extends_impl: 类名 | access_flags | 类型(e/i) | 父类/接口类名
        int count = 0;
        for (String line : readTsvFile(outputDir, "extends_impl")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            // 存储到 call_graph 表作为辅助数据，callType 用 EXTENDS 或 IMPLEMENTS 标记
            CallGraphEntity cg = new CallGraphEntity();
            cg.setRepoId(repoId);
            cg.setCallerMethod(cols[0]); // 子类
            cg.setCalleeMethod(cols[3]); // 父类/接口
            cg.setCallType("e".equals(cols[2]) ? "EXTENDS" : "IMPLEMENTS");
            cg.setEnabled(true);
            callGraphRepo.save(cg);
            count++;
        }
        logger.info("解析 extends_impl: {} 条", count);
    }

    private void parseMethodCatch(Long repoId, String outputDir, String repoPath) {
        // method_catch 实际列格式（见 docs/file_format.md 1.35）:
        // [0]完整方法 [1]返回类型 [2]catch异常类型 [3]catch标志(switch/try-with-resource)
        // [4]try开始行 [5]try结束行 [6]try最小调用ID [7]try最大调用ID
        // [8]catch开始偏移 [9]catch结束偏移 [10]catch开始行 [11]catch结束行 ...
        int count = 0;
        for (String line : readTsvFile(outputDir, "method_catch")) {
            String[] cols = line.split("\t");
            if (cols.length < 11) continue;

            String fullMethod = cols[0];
            String exceptionType = cols[2];
            String catchFlag = cols[3];
            // 跳过编译器生成的 switch / try-with-resource catch 块
            if (catchFlag != null && !catchFlag.isBlank()) continue;
            if (exceptionType == null || exceptionType.isBlank()) continue;

            Integer tryStart = parseIntSafe(cols[4]);
            Integer tryEnd = parseIntSafe(cols[5]);
            int catchLine = parseIntSafe(cols[10]) != null ? parseIntSafe(cols[10]) : 0;

            // 读取 catch 行的源码
            String catchSource = readSourceLine(repoPath, fullMethod, catchLine);

            StringBuilder context = new StringBuilder();
            context.append("catch ").append(shortClassName(exceptionType));
            if (catchSource != null && !catchSource.isEmpty()) {
                context.append("\n📝 ").append(catchSource.trim());
            }
            if (tryStart != null && tryEnd != null) {
                context.append("\n📍 try 范围: 行 ").append(tryStart).append(" ~ ").append(tryEnd);
            }

            BoundaryEntity boundary = new BoundaryEntity();
            boundary.setRepoId(repoId);
            boundary.setFullMethod(fullMethod);
            boundary.setBoundaryType("EXCEPTION");
            boundary.setLineNumber(catchLine);
            boundary.setContext(context.toString());
            boundaryRepo.save(boundary);
            count++;
        }
        logger.info("解析 method_catch: {} 条", count);
    }

    private void parseMethodThrow(Long repoId, String outputDir, String repoPath) {
        // method_throw 实际列格式（见 docs/file_format.md 1.44）:
        // [0]完整方法 [1]返回类型 [2]throw指令偏移量 [3]throw代码行号 [4]序号
        // [5]throw异常类型 [6]throw标志(ce/mcr/unk) ...
        int count = 0;
        for (String line : readTsvFile(outputDir, "method_throw")) {
            String[] cols = line.split("\t");
            if (cols.length < 6) continue;

            String fullMethod = cols[0];
            int throwLine = parseIntSafe(cols[3]) != null ? parseIntSafe(cols[3]) : 0;
            String exceptionType = cols[5];
            // mcr(抛方法调用返回值)等情况异常类型为空，跳过
            if (exceptionType == null || exceptionType.isBlank()) continue;

            // 从源码读取 throw 那一行的完整内容
            String throwDetail = readSourceLine(repoPath, fullMethod, throwLine);
            if (throwDetail != null) {
                throwDetail = throwDetail.trim();
                // 如果引用了枚举常量，尝试解析枚举值
                throwDetail = resolveEnumValues(repoPath, throwDetail);
            }

            StringBuilder context = new StringBuilder();
            context.append("throw ").append(shortClassName(exceptionType));
            if (throwDetail != null && !throwDetail.isEmpty()) {
                context.append("\n📝 ").append(throwDetail);
            }

            BoundaryEntity boundary = new BoundaryEntity();
            boundary.setRepoId(repoId);
            boundary.setFullMethod(fullMethod);
            boundary.setBoundaryType("EXCEPTION");
            boundary.setLineNumber(throwLine);
            boundary.setContext(context.toString());
            boundaryRepo.save(boundary);
            count++;
        }
        logger.info("解析 method_throw: {} 条", count);
    }

    /** 从源码文件读取指定行 */
    private String readSourceLine(String repoPath, String fullMethod, int lineNumber) {
        if (lineNumber <= 0) return null;
        String className = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(0, fullMethod.lastIndexOf(':')) : null;
        if (className == null) return null;

        String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relativePath = topLevel.replace('.', '/') + ".java";

        Path sourceFile = findSourceFileInRepo(Path.of(repoPath), relativePath);
        if (sourceFile == null) return null;

        try {
            List<String> lines = Files.readAllLines(sourceFile);
            // 读取 throw 行及后续几行（throw 语句可能跨行）
            if (lineNumber <= lines.size()) {
                StringBuilder sb = new StringBuilder();
                for (int i = lineNumber - 1; i < Math.min(lineNumber + 3, lines.size()); i++) {
                    String l = lines.get(i).trim();
                    sb.append(l);
                    if (l.endsWith(";") || l.endsWith("{") || l.endsWith("}")) break;
                    sb.append(" ");
                }
                return sb.toString();
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    /** 解析源码中引用的枚举常量值，如 ResponseCodeEnum.PAY_CENTER_REQUEST_FAILED → (4005, "支付中台请求异常") */
    private String resolveEnumValues(String repoPath, String throwLine) {
        // 匹配 EnumClass.CONSTANT 模式
        java.util.regex.Pattern enumPattern = java.util.regex.Pattern.compile("([A-Z][\\w]*)\\.([A-Z_]+)");
        java.util.regex.Matcher m = enumPattern.matcher(throwLine);

        String result = throwLine;
        Set<String> resolved = new HashSet<>();

        while (m.find()) {
            String enumClass = m.group(1);
            String enumConstant = m.group(2);
            String key = enumClass + "." + enumConstant;
            if (resolved.contains(key)) continue;
            resolved.add(key);

            // 在仓库中搜索枚举类文件
            String enumValue = findEnumConstantValue(repoPath, enumClass, enumConstant);
            if (enumValue != null) {
                result = result + "\n   → " + enumClass + "." + enumConstant + " = " + enumValue;
            }
        }
        return result;
    }

    /** 在仓库源码中查找枚举常量的定义值 */
    private String findEnumConstantValue(String repoPath, String enumClassName, String constantName) {
        // 搜索文件名匹配的 Java 文件
        try (var walk = Files.walk(Path.of(repoPath), 10)) {
            Optional<Path> enumFile = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(enumClassName + ".java"))
                    .findFirst();

            if (enumFile.isEmpty()) return null;

            List<String> lines = Files.readAllLines(enumFile.get());
            for (String line : lines) {
                String trimmed = line.trim();
                // 匹配 CONSTANT_NAME(value1, "value2") 或 CONSTANT_NAME("value")
                if (trimmed.startsWith(constantName + "(") || trimmed.startsWith(constantName + " (")) {
                    // 提取括号内的内容
                    int start = trimmed.indexOf('(');
                    int end = trimmed.lastIndexOf(')');
                    if (start >= 0 && end > start) {
                        return trimmed.substring(start, end + 1);
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    private Path findSourceFileInRepo(Path repoRoot, String relativePath) {
        Path direct = repoRoot.resolve("src/main/java").resolve(relativePath);
        if (Files.exists(direct)) return direct;
        try (var walk = Files.walk(repoRoot, 8)) {
            return walk
                    .filter(p -> "java".equals(p.getFileName().toString())
                            && p.getParent() != null && "main".equals(p.getParent().getFileName().toString())
                            && p.getParent().getParent() != null && "src".equals(p.getParent().getParent().getFileName().toString())
                            && Files.isDirectory(p))
                    .map(srcDir -> srcDir.resolve(relativePath))
                    .filter(Files::exists)
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    private String shortClassName(String fullClassName) {
        return fullClassName.contains(".") ? fullClassName.substring(fullClassName.lastIndexOf('.') + 1) : fullClassName;
    }

    private void parseSpringController(Long repoId, String outputDir, String repoPath) {

        // javacg2 不直接生成 spring_controller.txt，需要从 method_annotation.txt 和 class_annotation.txt 解析
        // 1. 先读取类级别的 @RequestMapping 前缀
        Map<String, String> classBasePaths = new HashMap<>();
        for (String line : readTsvFile(outputDir, "class_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 3) continue;
            String className = cols[0];
            String annotationClass = cols[1];
            // 检查是否是 @RequestMapping 或 @RestController
            if (annotationClass.equals("org.springframework.web.bind.annotation.RequestMapping")) {
                // 查找 value 或 path 属性
                for (int i = 2; i < cols.length - 1; i++) {
                    if ((cols[i].equals("value") || cols[i].equals("path")) && i + 1 < cols.length) {
                        String pathValue = cols[i + 1];
                        // 去掉 {} 包裹
                        if (pathValue.startsWith("{") && pathValue.endsWith("}")) {
                            pathValue = pathValue.substring(1, pathValue.length() - 1);
                        }
                        classBasePaths.put(className, pathValue);
                        break;
                    }
                }
            }
        }

        // 2. 读取方法级别的 @GetMapping/@PostMapping 等注解
        int count = 0;
        for (String line : readTsvFile(outputDir, "method_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;

            String fullMethod = cols[0];
            String annotationClass = cols[3];

            // 检查是否是 HTTP 方法映射注解
            String httpMethod = null;
            if (annotationClass.equals("org.springframework.web.bind.annotation.GetMapping")) {
                httpMethod = "GET";
            } else if (annotationClass.equals("org.springframework.web.bind.annotation.PostMapping")) {
                httpMethod = "POST";
            } else if (annotationClass.equals("org.springframework.web.bind.annotation.PutMapping")) {
                httpMethod = "PUT";
            } else if (annotationClass.equals("org.springframework.web.bind.annotation.DeleteMapping")) {
                httpMethod = "DELETE";
            } else if (annotationClass.equals("org.springframework.web.bind.annotation.PatchMapping")) {
                httpMethod = "PATCH";
            } else if (annotationClass.equals("org.springframework.web.bind.annotation.RequestMapping")) {
                httpMethod = "ALL";
            }

            if (httpMethod == null) continue;

            // 提取类名
            int colonIdx = fullMethod.lastIndexOf(':');
            if (colonIdx < 0) continue;
            String className = fullMethod.substring(0, colonIdx);

            // 查找 value 或 path 属性
            String methodPath = "";
            for (int i = 4; i < cols.length - 1; i++) {
                if ((cols[i].equals("value") || cols[i].equals("path")) && i + 1 < cols.length) {
                    String pathValue = cols[i + 1];
                    // 去掉 {} 包裹
                    if (pathValue.startsWith("{") && pathValue.endsWith("}")) {
                        pathValue = pathValue.substring(1, pathValue.length() - 1);
                    }
                    methodPath = pathValue;
                    break;
                }
            }

            // 拼接完整 URL 路径
            String basePath = classBasePaths.getOrDefault(className, "");
            String fullPath = basePath + methodPath;
            if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;

            // 只识别有源码的业务类（过滤依赖库）
            if (!hasSourceFile(repoPath, className)) continue;

            ApiEndpointEntity endpoint = new ApiEndpointEntity();
            endpoint.setRepoId(repoId);
            endpoint.setEndpointType("CONTROLLER");
            endpoint.setUrlPath(fullPath);
            endpoint.setAnnotationClass(annotationClass);
            endpoint.setClassName(className);
            endpoint.setFullMethod(fullMethod);
            endpoint.setHttpMethod(httpMethod);

            apiEndpointRepo.save(endpoint);
            count++;
        }
        logger.info("解析 spring_controller: {} 条", count);
        buildLogService.append(repoId, "解析 Spring 控制器: " + count + " 个");
    }

    /**
     * 从 method_annotation 中识别 Kafka/RocketMQ/Scheduled/gRPC 等入口
     */
    private void parseListenerEndpoints(Long repoId, String outputDir, String repoPath) {

        // method_annotation 实际格式: 完整方法 | 返回类型 | jar序号 | 注解类名 | 属性名 | 属性值
        // 注解 -> [入口类型, 描述前缀]
        Map<String, String[]> listenerAnnotations = Map.ofEntries(
                // Kafka
                Map.entry("org.springframework.kafka.annotation.KafkaListener", new String[]{"KAFKA", "Kafka 消费者"}),
                Map.entry("org.springframework.kafka.annotation.KafkaHandler", new String[]{"KAFKA", "Kafka Handler"}),
                // RocketMQ
                Map.entry("org.apache.rocketmq.spring.annotation.RocketMQMessageListener", new String[]{"ROCKETMQ", "RocketMQ 消费者"}),
                // RabbitMQ
                Map.entry("org.springframework.amqp.rabbit.annotation.RabbitListener", new String[]{"RABBITMQ", "RabbitMQ 消费者"}),
                Map.entry("org.springframework.amqp.rabbit.annotation.RabbitHandler", new String[]{"RABBITMQ", "RabbitMQ Handler"}),
                // 定时任务
                Map.entry("org.springframework.scheduling.annotation.Scheduled", new String[]{"SCHEDULED", "定时任务"}),
                // gRPC
                Map.entry("io.grpc.stub.annotations.RpcMethod", new String[]{"GRPC", "gRPC 服务"}),
                Map.entry("net.devh.boot.grpc.server.service.GrpcService", new String[]{"GRPC", "gRPC 服务"})
        );

        // 先收集每个方法的注解信息（方法 -> 注解类名 -> 属性值列表）
        Map<String, Map<String, Map<String, String>>> methodAnnotations = new LinkedHashMap<>();
        for (String line : readTsvFile(outputDir, "method_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            String fullMethod = cols[0];
            // cols[1]=returnType, cols[2]=jarNum, cols[3]=annotationClass
            String annotationClass = cols[3];

            if (!listenerAnnotations.containsKey(annotationClass)) continue;

            methodAnnotations
                    .computeIfAbsent(fullMethod, k -> new LinkedHashMap<>())
                    .computeIfAbsent(annotationClass, k -> new LinkedHashMap<>());

            // cols[4]=attrName, cols[5]=attrValue
            if (cols.length >= 6 && !cols[4].isEmpty()) {
                methodAnnotations.get(fullMethod).get(annotationClass).put(cols[4], cols[5]);
            }
        }

        // 也从 class_annotation 中识别 gRPC 服务类
        Set<String> grpcServiceClasses = new HashSet<>();
        for (String line : readTsvFile(outputDir, "class_annotation")) {
            String[] cols = line.split("\t");
            if (cols.length < 2) continue;
            if (cols[1].contains("GrpcService") || cols[1].contains("grpc")) {
                String cls = cols[0];
                if (hasSourceFile(repoPath, cls)) {
                    grpcServiceClasses.add(cls);
                }
            }
        }

        int count = 0;
        Set<String> seen = new HashSet<>();

        for (Map.Entry<String, Map<String, Map<String, String>>> entry : methodAnnotations.entrySet()) {
            String fullMethod = entry.getKey();
            if (seen.contains(fullMethod)) continue;
            seen.add(fullMethod);

            for (Map.Entry<String, Map<String, String>> annoEntry : entry.getValue().entrySet()) {
                String annotationClass = annoEntry.getKey();
                Map<String, String> attrs = annoEntry.getValue();
                String[] typeInfo = listenerAnnotations.get(annotationClass);
                if (typeInfo == null) continue;

                String endpointType = typeInfo[0];
                String description = typeInfo[1];

                // 提取关键属性（topic、cron 等）
                String urlPath = "";
                if (attrs.containsKey("topics")) urlPath = "topic:" + attrs.get("topics");
                else if (attrs.containsKey("topic")) urlPath = "topic:" + attrs.get("topic");
                else if (attrs.containsKey("value")) urlPath = attrs.get("value");
                else if (attrs.containsKey("queues")) urlPath = "queue:" + attrs.get("queues");
                else if (attrs.containsKey("cron")) urlPath = "cron:" + attrs.get("cron");
                else if (attrs.containsKey("fixedRate")) urlPath = "fixedRate:" + attrs.get("fixedRate");
                else if (attrs.containsKey("fixedDelay")) urlPath = "fixedDelay:" + attrs.get("fixedDelay");

                int colonIdx = fullMethod.lastIndexOf(':');
                String className = colonIdx > 0 ? fullMethod.substring(0, colonIdx) : "";

                // 只识别有源码的业务类（过滤依赖库）
                if (!hasSourceFile(repoPath, className)) continue;

                ApiEndpointEntity endpoint = new ApiEndpointEntity();
                endpoint.setRepoId(repoId);
                endpoint.setEndpointType(endpointType);
                endpoint.setUrlPath(urlPath);
                endpoint.setAnnotationClass(annotationClass);
                endpoint.setClassName(className);
                endpoint.setFullMethod(fullMethod);
                endpoint.setHttpMethod(null);

                apiEndpointRepo.save(endpoint);
                count++;
            }
        }

        // gRPC 服务类的所有 public 方法也作为入口
        if (!grpcServiceClasses.isEmpty()) {
            for (ChunkEntity chunk : chunkRepo.findByRepoId(repoId)) {
                if (chunk.getClassName() != null && grpcServiceClasses.contains(chunk.getClassName())) {
                    if (chunk.getAccessFlags() != null && chunk.getAccessFlags().contains("public")
                            && !seen.contains(chunk.getFullMethod())) {
                        ApiEndpointEntity endpoint = new ApiEndpointEntity();
                        endpoint.setRepoId(repoId);
                        endpoint.setEndpointType("GRPC");
                        endpoint.setUrlPath("grpc:" + chunk.getMethodName());
                        endpoint.setClassName(chunk.getClassName());
                        endpoint.setFullMethod(chunk.getFullMethod());
                        apiEndpointRepo.save(endpoint);
                        count++;
                    }
                }
            }
        }

        logger.info("注解识别入口: {} 条", count);

        // ===== 第二部分：通过继承关系识别自定义封装的入口 =====
        // 读取 extends_impl 数据，找到继承了 MQ/gRPC 基类的用户类
        // 关键词匹配：类名或父类名包含这些关键词的，识别为对应入口类型
        Map<String, String> inheritanceKeywords = Map.ofEntries(
                // MQ 消费者基类关键词
                Map.entry("MessageConsumer", "MQ"),
                Map.entry("MessageListener", "MQ"),
                Map.entry("Consumer", "MQ"),
                Map.entry("MqConsumer", "MQ"),
                Map.entry("KafkaConsumer", "KAFKA"),
                Map.entry("RocketMQListener", "ROCKETMQ"),
                Map.entry("RabbitConsumer", "RABBITMQ"),
                // gRPC
                Map.entry("ImplBase", "GRPC"),
                Map.entry("GrpcService", "GRPC"),
                Map.entry("RpcService", "GRPC"),
                Map.entry("BlockingStub", "GRPC"),
                // 定时任务
                Map.entry("TimerTask", "SCHEDULED"),
                Map.entry("QuartzJobBean", "SCHEDULED"),
                Map.entry("Job", "SCHEDULED")
        );

        // 读取用户包前缀

        // 从 extends_impl 找继承关系
        Map<String, Set<String>> classParents = new HashMap<>(); // 子类 -> 父类/接口集合
        for (String line : readTsvFile(outputDir, "extends_impl")) {
            String[] cols = line.split("\t");
            if (cols.length < 4) continue;
            String childClass = cols[0];
            String parentClass = cols[3];
            classParents.computeIfAbsent(childClass, k -> new HashSet<>()).add(parentClass);
        }

        // 找到用户包下继承了 MQ/gRPC 基类的类
        Set<String> mqConsumerClasses = new HashSet<>();
        Set<String> grpcClasses = new HashSet<>();
        Set<String> scheduledClasses = new HashSet<>();

        for (Map.Entry<String, Set<String>> entry : classParents.entrySet()) {
            String childClass = entry.getKey();
            // 只识别有源码的业务类
            if (!hasSourceFile(repoPath, childClass)) continue;

            for (String parent : entry.getValue()) {
                String parentShort = parent.contains(".") ? parent.substring(parent.lastIndexOf('.') + 1) : parent;
                for (Map.Entry<String, String> kw : inheritanceKeywords.entrySet()) {
                    if (parentShort.contains(kw.getKey()) || parent.contains(kw.getKey())) {
                        String type = kw.getValue();
                        if ("MQ".equals(type) || "KAFKA".equals(type) || "ROCKETMQ".equals(type) || "RABBITMQ".equals(type)) {
                            mqConsumerClasses.add(childClass);
                        } else if ("GRPC".equals(type)) {
                            grpcClasses.add(childClass);
                        } else if ("SCHEDULED".equals(type)) {
                            scheduledClasses.add(childClass);
                        }
                        break;
                    }
                }
            }
        }

        logger.info("继承关系识别: MQ消费者类 {} 个, gRPC类 {} 个, 定时任务类 {} 个",
                mqConsumerClasses.size(), grpcClasses.size(), scheduledClasses.size());

        // 为这些类的核心方法创建入口点
        // MQ 消费者的核心方法名
        Set<String> mqMethodNames = Set.of("onMessage", "consume", "handleMessage", "handle", "process",
                "onSingleMessage", "onBatchMessage", "receiveMessage", "listener", "execute",
                "consumeRawMsg", "consumeMsg", "consumeMessage", "processMessage", "handleMsg",
                "onMsg", "doConsume", "doHandle", "doProcess");
        // gRPC 的核心方法排除 getter/setter
        Set<String> skipMethods = Set.of("equals", "hashCode", "toString", "getClass", "wait", "notify", "notifyAll");

        int inheritCount = 0;
        for (ChunkEntity chunk : chunkRepo.findByRepoId(repoId)) {
            if (chunk.getClassName() == null || seen.contains(chunk.getFullMethod())) continue;
            String methodName = chunk.getMethodName();
            if (methodName == null || methodName.startsWith("<") || skipMethods.contains(methodName)) continue;

            // MQ 消费者
            if (mqConsumerClasses.contains(chunk.getClassName())) {
                boolean isCoreMethod = mqMethodNames.contains(methodName)
                        || (chunk.getAccessFlags() != null
                            && (chunk.getAccessFlags().contains("public") || chunk.getAccessFlags().contains("protected"))
                            && !methodName.startsWith("get") && !methodName.startsWith("set") && !methodName.startsWith("is"));
                if (isCoreMethod) {
                    ApiEndpointEntity endpoint = new ApiEndpointEntity();
                    endpoint.setRepoId(repoId);
                    endpoint.setEndpointType("MQ");
                    endpoint.setUrlPath("mq:" + chunk.getClassName().substring(chunk.getClassName().lastIndexOf('.') + 1) + "." + methodName);
                    endpoint.setClassName(chunk.getClassName());
                    endpoint.setFullMethod(chunk.getFullMethod());
                    apiEndpointRepo.save(endpoint);
                    seen.add(chunk.getFullMethod());
                    inheritCount++;
                }
            }

            // gRPC 服务
            if (grpcClasses.contains(chunk.getClassName())) {
                if (chunk.getAccessFlags() != null && chunk.getAccessFlags().contains("public")
                        && !methodName.startsWith("get") && !methodName.startsWith("set")) {
                    ApiEndpointEntity endpoint = new ApiEndpointEntity();
                    endpoint.setRepoId(repoId);
                    endpoint.setEndpointType("GRPC");
                    endpoint.setUrlPath("grpc:" + chunk.getClassName().substring(chunk.getClassName().lastIndexOf('.') + 1) + "." + methodName);
                    endpoint.setClassName(chunk.getClassName());
                    endpoint.setFullMethod(chunk.getFullMethod());
                    apiEndpointRepo.save(endpoint);
                    seen.add(chunk.getFullMethod());
                    inheritCount++;
                }
            }

            // 定时任务
            if (scheduledClasses.contains(chunk.getClassName())) {
                if ("execute".equals(methodName) || "run".equals(methodName) || "doExecute".equals(methodName)
                        || (chunk.getAccessFlags() != null && chunk.getAccessFlags().contains("public")
                            && !methodName.startsWith("get") && !methodName.startsWith("set"))) {
                    ApiEndpointEntity endpoint = new ApiEndpointEntity();
                    endpoint.setRepoId(repoId);
                    endpoint.setEndpointType("SCHEDULED");
                    endpoint.setUrlPath("task:" + chunk.getClassName().substring(chunk.getClassName().lastIndexOf('.') + 1) + "." + methodName);
                    endpoint.setClassName(chunk.getClassName());
                    endpoint.setFullMethod(chunk.getFullMethod());
                    apiEndpointRepo.save(endpoint);
                    seen.add(chunk.getFullMethod());
                    inheritCount++;
                }
            }
        }

        count += inheritCount;
        logger.info("识别入口总计: 注解 {} + 继承 {} = {} 条", count - inheritCount, inheritCount, count);
        buildLogService.append(repoId, "识别入口: 注解 " + (count - inheritCount) + " + 继承 " + inheritCount + " = " + count + " 个");
    }

    private void parseSpringBean(Long repoId, String outputDir) {
        // spring_bean: Bean名称 | 序号 | 类名 | profile | 定义方式 | 注解类名 | 所在类名/文件路径
        int count = 0;
        for (String line : readTsvFile(outputDir, "spring_bean")) {
            String[] cols = line.split("\t");
            if (cols.length < 3) continue;
            // Spring Bean 信息暂存到日志，后续用于类型替换
            logger.debug("Spring Bean: name={}, class={}", cols[0], cols[2]);
            count++;
        }
        logger.info("解析 spring_bean: {} 条", count);
    }

    // ========== 工具方法 ==========

    private List<String> readTsvFile(String outputDir, String fileName) {
        // javacg2 输出文件默认后缀为 .txt
        Path filePath = Path.of(outputDir, fileName + ".txt");
        if (!Files.exists(filePath)) {
            logger.warn("输出文件不存在: {}", filePath);
            return List.of();
        }
        try {
            return Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("读取文件失败: {}", filePath, e);
            return List.of();
        }
    }

    private void parseMethodSignature(ChunkEntity chunk, String fullMethod) {
        // 格式: com.example.ClassName:methodName(param1,param2)
        int colonIdx = fullMethod.lastIndexOf(':');
        if (colonIdx > 0) {
            String className = fullMethod.substring(0, colonIdx);
            chunk.setClassName(className);

            int lastDot = className.lastIndexOf('.');
            chunk.setPackageName(lastDot > 0 ? className.substring(0, lastDot) : "");

            String methodPart = fullMethod.substring(colonIdx + 1);
            int parenIdx = methodPart.indexOf('(');
            chunk.setMethodName(parenIdx > 0 ? methodPart.substring(0, parenIdx) : methodPart);
        }
    }

    private Integer parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 判断类是否有源码（有源码 = 业务代码，无源码 = 依赖库），带缓存 */
    private final Map<String, Boolean> sourceFileCache = new HashMap<>();
    private boolean hasSourceFile(String repoPath, String className) {
        if (className == null || repoPath == null) return false;
        String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        return sourceFileCache.computeIfAbsent(repoPath + "|" + topLevel, k -> {
            String relativePath = topLevel.replace('.', '/') + ".java";
            return findSourceFileInRepo(Path.of(repoPath), relativePath) != null;
        });
    }

    /** 获取用户包前缀配置 */
    private List<String> getUserPackagePrefixes(Long repoId) {
        String raw = configExtractor.getEffectiveValue(repoId, "analyze.package.prefix", null);
        List<String> prefixes = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split("[,;\\s]+")) {
                if (!p.isBlank()) prefixes.add(p.trim());
            }
        }
        return prefixes;
    }

    /** 将数字形式的 access flags 转为可读字符串 */
    private String parseAccessFlags(String flagStr) {
        try {
            int flags = Integer.parseInt(flagStr.trim());
            List<String> parts = new ArrayList<>();
            if ((flags & 0x0001) != 0) parts.add("public");
            if ((flags & 0x0002) != 0) parts.add("private");
            if ((flags & 0x0004) != 0) parts.add("protected");
            if ((flags & 0x0008) != 0) parts.add("static");
            if ((flags & 0x0010) != 0) parts.add("final");
            if ((flags & 0x0020) != 0) parts.add("synchronized");
            if ((flags & 0x0100) != 0) parts.add("native");
            if ((flags & 0x0400) != 0) parts.add("abstract");
            return parts.isEmpty() ? flagStr : String.join(" ", parts);
        } catch (NumberFormatException e) {
            return flagStr;
        }
    }
}
