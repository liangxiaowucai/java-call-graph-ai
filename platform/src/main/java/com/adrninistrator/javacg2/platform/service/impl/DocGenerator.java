package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.*;
import com.adrninistrator.javacg2.platform.repository.*;
import com.adrninistrator.javacg2.platform.service.BuildLogService;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DocGenerator.class);

    private final CallGraphEngine callGraphEngine;
    private final BoundaryRepo boundaryRepo;
    private final CallChainCodeGenerator codeGenerator;
    private final ClaudeApiClient claudeClient;
    private final ApiEndpointRepo apiEndpointRepo;
    private final ChunkRepo chunkRepo;
    private final RepositoryRepo repositoryRepo;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final RepoConfigRepo repoConfigRepo;
    private final BuildLogService buildLogService;

    public DocGenerator(CallGraphEngine callGraphEngine, BoundaryRepo boundaryRepo,
                         CallChainCodeGenerator codeGenerator, ClaudeApiClient claudeClient,
                         ApiEndpointRepo apiEndpointRepo, ChunkRepo chunkRepo,
                         RepositoryRepo repositoryRepo, ProjectInfoExtractor projectInfoExtractor,
                         RepoConfigRepo repoConfigRepo, BuildLogService buildLogService) {
        this.callGraphEngine = callGraphEngine;
        this.boundaryRepo = boundaryRepo;
        this.codeGenerator = codeGenerator;
        this.claudeClient = claudeClient;
        this.apiEndpointRepo = apiEndpointRepo;
        this.chunkRepo = chunkRepo;
        this.repositoryRepo = repositoryRepo;
        this.projectInfoExtractor = projectInfoExtractor;
        this.repoConfigRepo = repoConfigRepo;
        this.buildLogService = buildLogService;
    }

    // AI 文档生成的 system prompt
    private static final String DOC_SYSTEM_PROMPT = ""
            + "你是一个源码分析专家，正在将代码逻辑翻译成所有人都能看懂的文档。\n"
            + "你的读者可能是产品经理、测试工程师、客服人员或新入职的研发。\n\n"
            + "## 核心原则\n"
            + "1. 用业务语言描述，不要贴代码片段\n"
            + "2. 每个结论必须标注来源：(见 `类名.方法名`)\n"
            + "3. 校验规则要具体到值：不要说\"有校验\"，要说\"商品名称必填，最多50个字符\"\n"
            + "4. 异常场景要说清触发条件和用户感知\n"
            + "5. 严禁编造任何内容：所有技术组件、外部依赖、字段、逻辑、状态，必须在提供的源码或数据中有明确依据才能写出，不得根据常识或经验推断补全\n"
            + "6. 如果提供的信息不足以确定某项内容，直接省略该项，不要猜测或假设\n"
            + "7. 使用 Mermaid 图表（sequenceDiagram/flowchart/stateDiagram），图表中的节点只能来自已提供的信息\n"
            + "8. 只输出 Markdown，不要解释性文字\n";

    /**
     * AI 生成完整项目概览文档（异步调用）
     */
    public String generateAIOverview(Long repoId) {
        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return "仓库不存在";

        List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);
        List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);

        StringBuilder fullDoc = new StringBuilder();

        // ========== 第 1 轮：AI 项目简介 + 技术栈 + 架构图 + 外部依赖 + 功能模块 ==========
        buildLogService.append(repoId, "📖 [1/4] 生成项目简介和架构信息...");
        try {
            String round1 = generateRound1(repo, endpoints, allBoundaries);
            fullDoc.append(round1).append("\n\n");
            buildLogService.append(repoId, "✅ [1/4] 项目简介和架构信息完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第1轮失败", e);
            buildLogService.append(repoId, "【异常点】 [1/4] 失败: " + e.getMessage());
            fullDoc.append("## 项目简介\n\n> 生成失败，请重试\n\n");
        }

        // ========== 项目结构（代码直出，不过 AI）==========
        buildLogService.append(repoId, "📖 [2/4] 生成项目结构...");
        try {
            fullDoc.append(generateProjectStructure(repoId, endpoints)).append("\n\n");
            buildLogService.append(repoId, "✅ [2/4] 项目结构完成");
        } catch (Exception e) {
            logger.warn("[文档生成] 项目结构生成失败", e);
            buildLogService.append(repoId, "⚠️ [2/4] 项目结构生成跳过: " + e.getMessage());
        }

        // ========== 第 3 轮：每个接口的完整档案 ==========
        buildLogService.append(repoId, "📖 [3/4] 生成接口档案（共 " + endpoints.size() + " 个）...");
        fullDoc.append("## 接口详情\n\n");
        int done = 0;
        for (ApiEndpointEntity ep : endpoints) {
            try {
                String epDoc = generateEndpointDoc(repoId, repo, ep);
                fullDoc.append(epDoc).append("\n\n");
                done++;
                if (done % 5 == 0) {
                    buildLogService.append(repoId, "📖 [3/4] 已完成 " + done + "/" + endpoints.size() + " 个接口");
                }
            } catch (Exception e) {
                logger.warn("[文档生成] 接口档案失败: {}", ep.getFullMethod(), e);
                String shortName = shortClass(ep.getClassName()) + "." + shortMethodName(ep.getFullMethod());
                fullDoc.append("### ").append(shortName).append("\n\n> 生成失败\n\n");
            }
        }
        buildLogService.append(repoId, "✅ [3/4] 接口档案完成（" + done + "/" + endpoints.size() + "）");

        // ========== 第 4 轮：状态机 + 错误码 + 风险点 ==========
        buildLogService.append(repoId, "📖 [4/4] 生成状态流转和风险分析...");
        try {
            String round3 = generateRound3(repoId, repo, allBoundaries);
            fullDoc.append(round3).append("\n\n");
            buildLogService.append(repoId, "✅ [4/4] 状态流转和风险分析完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第4轮失败", e);
            buildLogService.append(repoId, "【异常点】 [4/4] 失败: " + e.getMessage());
        }

        // 追加静态统计
        fullDoc.append(generateStaticSections(repoId, repo));

        return fullDoc.toString();
    }

    /**
     * 第 1 轮：AI（项目简介 + 功能模块）+ 技术栈 + 架构图 + 外部依赖（代码直出）
     * 顺序：项目简介 → 技术栈 → 架构图 → 外部依赖 → 功能模块
     */
    private String generateRound1(RepositoryEntity repo, List<ApiEndpointEntity> endpoints,
                                   List<BoundaryEntity> allBoundaries) {
        var projectInfo = projectInfoExtractor.extract(repo.getLocalPath());

        // ===== 外部依赖列表（去重，用于架构图和依赖章节）=====
        List<String[]> deps = new ArrayList<>(); // [type, context]
        Set<String> seenDeps = new HashSet<>();
        for (BoundaryEntity b : allBoundaries) {
            if ("HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType()) || "MQ".equals(b.getBoundaryType())) {
                String firstLine = b.getContext() != null ? b.getContext().split("\n")[0] : "";
                String key = b.getBoundaryType() + "|" + firstLine;
                if (seenDeps.add(key)) {
                    deps.add(new String[]{b.getBoundaryType(), firstLine});
                }
            }
        }

        // ===== 代码直出部分 =====
        String techStackMd = buildTechStackMd(repo.getName(), projectInfo);
        String archMd = buildArchMd(repo.getId(), repo.getName(), deps);
        String externalDepsMd = buildExternalDepsMd(repo.getId(), deps);

        // ===== 接口清单：供 AI 生成项目简介和功能模块 =====
        StringBuilder endpointList = new StringBuilder();
        for (ApiEndpointEntity ep : endpoints) {
            String shortCls = shortClass(ep.getClassName());
            String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType();
            String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
            String desc = "";
            var chunk = chunkRepo.findByRepoIdAndFullMethod(repo.getId(), ep.getFullMethod());
            if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                for (String part : chunk.get().getCallSummary().split("\\|")) {
                    String trimmed = part.trim();
                    if (trimmed.length() >= 2 && trimmed.length() <= 30) { desc = trimmed; break; }
                }
            }
            endpointList.append("- ").append(method).append(" ").append(url)
                    .append(" (").append(shortCls).append(")");
            if (!desc.isEmpty()) endpointList.append(" — ").append(desc);
            endpointList.append("\n");
        }

        // ===== AI：项目简介（1段）+ 功能模块（分组清单）=====
        String prompt = "以下是从源码中静态提取的真实接口列表，请严格基于此列表生成内容，不要添加列表中不存在的任何内容。\n\n"
                + "## 接口列表（共 " + endpoints.size() + " 个）\n"
                + endpointList + "\n"
                + "## 请生成以下两项内容\n\n"
                + "### 项目简介\n"
                + "用 2-3 句话描述这个项目是什么、服务于谁、核心能力是什么。只根据接口列表描述，不要添加接口中不存在的功能。\n\n"
                + "### 功能模块\n"
                + "按业务域将上述接口分组，每个模块一句话描述职责，列出该模块下的接口路径（格式：`METHOD /path — 简介`）。只做归类，不要新增或推断不存在的模块。\n";

        String aiPart = claudeClient.chat(DOC_SYSTEM_PROMPT, List.of(Map.of("role", "user", "content", prompt)));

        // 文档顺序：AI 项目简介 → 技术栈 → 架构图 → 外部依赖 → AI 功能模块
        return aiPart + "\n\n"
                + techStackMd + "\n\n"
                + archMd + "\n\n"
                + externalDepsMd;
    }

    /** 技术栈 Markdown —— 代码直出，不过 AI */
    private String buildTechStackMd(String repoName, ProjectInfoExtractor.ProjectInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 技术栈\n\n");
        sb.append("| 项目 | 值 |\n|------|-----|\n");
        sb.append("| 项目名称 | ").append(repoName).append(" |\n");
        if (info.buildTool != null)       sb.append("| 构建工具 | ").append(info.buildTool).append(" |\n");
        if (info.javaVersion != null)     sb.append("| Java 版本 | ").append(info.javaVersion).append(" |\n");
        if (info.springBootVersion != null) sb.append("| Spring Boot | ").append(info.springBootVersion).append(" |\n");

        // 关键框架（和 ProjectInfoExtractor 保持一致，直接输出检测到的）
        Map<String, String> fw = new LinkedHashMap<>();
        for (Map.Entry<String, String> dep : info.dependencies.entrySet()) {
            String k = dep.getKey().toLowerCase();
            if (k.contains("mybatis-plus"))                                   fw.put("MyBatis-Plus", dep.getValue());
            else if (k.contains("mybatis") && !k.contains("plus"))            fw.put("MyBatis", dep.getValue());
            else if (k.contains("spring-data-redis") || k.contains("jedis") || k.contains("lettuce")) fw.put("Redis", dep.getValue());
            else if (k.contains("kafka"))                                     fw.put("Kafka", dep.getValue());
            else if (k.contains("rocketmq"))                                  fw.put("RocketMQ", dep.getValue());
            else if (k.contains("rabbitmq") || k.contains("amqp"))           fw.put("RabbitMQ", dep.getValue());
            else if (k.contains("grpc"))                                      fw.put("gRPC", dep.getValue());
            else if (k.contains("mysql"))                                     fw.put("MySQL", dep.getValue());
            else if (k.contains("postgresql"))                                fw.put("PostgreSQL", dep.getValue());
            else if (k.contains("elasticsearch"))                             fw.put("Elasticsearch", dep.getValue());
            else if (k.contains("mongodb"))                                   fw.put("MongoDB", dep.getValue());
            else if (k.contains("swagger") || k.contains("springdoc"))       fw.put("Swagger/OpenAPI", dep.getValue());
            else if (k.contains("feign"))                                     fw.put("Feign", dep.getValue());
            else if (k.contains("dubbo"))                                     fw.put("Dubbo", dep.getValue());
            else if (k.contains("nacos"))                                     fw.put("Nacos", dep.getValue());
        }
        for (Map.Entry<String, String> e : fw.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ");
            sb.append(e.getValue() != null && !e.getValue().isBlank() ? e.getValue() : "-");
            sb.append(" |\n");
        }
        return sb.toString();
    }

    /** 外部依赖 Markdown：整合源码边界点 + 配置文件实际地址 */
    private String buildExternalDepsMd(Long repoId, List<String[]> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 外部依赖\n\n");

        Map<String, List<String>> cfg = buildExternalConfigMap(repoId);

        // ── 数据库 ──
        if (!cfg.get("DB").isEmpty()) {
            sb.append("### 数据库\n\n");
            sb.append("| 配置项 | 地址 |\n|--------|------|\n");
            for (String entry : cfg.get("DB")) sb.append("| ").append(entry).append(" |\n");
            sb.append("\n");
        }

        // ── Redis ──
        if (!cfg.get("REDIS").isEmpty()) {
            sb.append("### Redis\n\n");
            sb.append("| 配置项 | 值 |\n|--------|----|\n");
            for (String entry : cfg.get("REDIS")) sb.append("| ").append(entry).append(" |\n");
            sb.append("\n");
        }

        // ── HTTP 外部调用 ──
        Set<String> httpSvcs = new LinkedHashSet<>();
        for (String[] d : deps) { if ("HTTP".equals(d[0])) httpSvcs.add(extractServiceName(d[0], d[1])); }
        if (!httpSvcs.isEmpty() || !cfg.get("HTTP").isEmpty()) {
            sb.append("### HTTP 外部调用\n\n");
            if (!httpSvcs.isEmpty()) {
                sb.append("**调用方（源码）：** ").append(String.join("、", httpSvcs)).append("\n\n");
            }
            if (!cfg.get("HTTP").isEmpty()) {
                sb.append("**地址配置：**\n\n");
                sb.append("| 配置项 | 值 |\n|--------|----|\n");
                for (String entry : cfg.get("HTTP")) sb.append("| ").append(entry).append(" |\n");
            }
            sb.append("\n");
        }

        // ── 消息队列 ──
        Set<String> mqTopics = new LinkedHashSet<>();
        for (String[] d : deps) { if ("MQ".equals(d[0])) mqTopics.add(extractServiceName(d[0], d[1])); }
        if (!mqTopics.isEmpty() || !cfg.get("KAFKA").isEmpty() || !cfg.get("ROCKETMQ").isEmpty()) {
            sb.append("### 消息队列\n\n");
            if (!cfg.get("KAFKA").isEmpty()) {
                sb.append("**Kafka：**\n\n| 配置项 | 值 |\n|--------|----|\n");
                for (String entry : cfg.get("KAFKA")) sb.append("| ").append(entry).append(" |\n");
                sb.append("\n");
            }
            if (!cfg.get("ROCKETMQ").isEmpty()) {
                sb.append("**RocketMQ：**\n\n| 配置项 | 值 |\n|--------|----|\n");
                for (String entry : cfg.get("ROCKETMQ")) sb.append("| ").append(entry).append(" |\n");
                sb.append("\n");
            }
            if (!mqTopics.isEmpty()) {
                sb.append("**Topic（源码）：** ").append(String.join("、", mqTopics)).append("\n\n");
            }
        }

        // ── gRPC ──
        Set<String> grpcSvcs = new LinkedHashSet<>();
        for (String[] d : deps) { if ("GRPC".equals(d[0])) grpcSvcs.add(extractServiceName(d[0], d[1])); }
        if (!grpcSvcs.isEmpty() || !cfg.get("GRPC").isEmpty()) {
            sb.append("### gRPC\n\n");
            if (!grpcSvcs.isEmpty()) {
                sb.append("**服务（源码）：** ").append(String.join("、", grpcSvcs)).append("\n\n");
            }
            if (!cfg.get("GRPC").isEmpty()) {
                sb.append("| 配置项 | 值 |\n|--------|----|\n");
                for (String entry : cfg.get("GRPC")) sb.append("| ").append(entry).append(" |\n");
            }
            sb.append("\n");
        }

        if (cfg.values().stream().allMatch(List::isEmpty) && deps.isEmpty()) {
            sb.append("源码中未检测到外部依赖。\n");
        }
        return sb.toString();
    }

    /**
     * 从 RepoConfigEntity 中按类型提取实际地址配置
     * 返回 map: DB / REDIS / HTTP / KAFKA / ROCKETMQ / GRPC → ["configKey | value", ...]
     */
    private Map<String, List<String>> buildExternalConfigMap(Long repoId) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String k : new String[]{"DB", "REDIS", "HTTP", "KAFKA", "ROCKETMQ", "GRPC"}) {
            result.put(k, new ArrayList<>());
        }
        var configs = repoConfigRepo.findByRepoId(repoId);
        if (configs == null) return result;

        Set<String> seen = new HashSet<>();
        for (var c : configs) {
            if (c.getConfigValue() == null || c.getConfigValue().isBlank()) continue;
            if (!seen.add(c.getConfigKey())) continue;
            String k = c.getConfigKey().toLowerCase();
            String v = desensitize(c.getConfigKey(), c.getConfigValue());
            String entry = "`" + c.getConfigKey() + "` | `" + v + "`";

            if (k.contains("datasource") && (k.endsWith(".url") || k.contains(".jdbc-url") || k.contains(".druid.url"))) {
                result.get("DB").add(entry);
            } else if ((k.contains("redis") || k.contains("redisson")) &&
                    (k.contains("host") || k.contains("nodes") || k.contains("address") || k.contains("sentinel") || k.contains("master"))) {
                result.get("REDIS").add(entry);
            } else if (k.contains("kafka") && (k.contains("bootstrap") || k.contains("servers"))) {
                result.get("KAFKA").add(entry);
            } else if (k.contains("kafka") && k.contains("topic")) {
                result.get("KAFKA").add(entry);
            } else if (k.contains("rocketmq") && (k.contains("name-server") || k.contains("namesrv") || k.contains("host") || k.contains("topic"))) {
                result.get("ROCKETMQ").add(entry);
            } else if (k.contains("grpc") && (k.contains("address") || k.contains("host") || k.contains("port") || k.contains("target") || k.contains("server"))) {
                result.get("GRPC").add(entry);
            } else if (!k.contains("datasource") && !k.contains("redis") && !k.contains("kafka")
                    && !k.contains("rocketmq") && !k.contains("grpc") && !k.contains("server.port")
                    && !k.contains("spring.application") && !k.contains("management.")
                    && (k.contains(".url") || k.contains(".base-url") || k.contains(".baseurl")
                        || k.contains(".host") || k.contains(".endpoint") || k.contains(".address"))) {
                result.get("HTTP").add(entry);
            }
        }
        return result;
    }

    /** 架构图 Markdown：节点用服务名，图下附地址速查表 */
    private String buildArchMd(Long repoId, String repoName, List<String[]> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 系统架构图\n\n");

        Map<String, List<String>> cfg = buildExternalConfigMap(repoId);
        boolean hasDeps = !deps.isEmpty() || cfg.values().stream().anyMatch(l -> !l.isEmpty());
        if (!hasDeps) {
            sb.append("源码中未检测到外部依赖，无法生成架构图。\n");
            return sb.toString();
        }

        sb.append("```mermaid\n");
        sb.append("graph LR\n");
        String sysId = repoName.replaceAll("[^a-zA-Z0-9_]", "_");
        sb.append("    ").append(sysId).append("([\"").append(repoName).append("\"])\n");

        // 来自边界点的服务节点（HTTP/GRPC/MQ），按服务名去重
        Map<String, String> serviceToNodeId = new LinkedHashMap<>();
        int idx = 0;
        for (String[] d : deps) {
            String serviceName = extractServiceName(d[0], d[1]);
            String key = d[0] + "|" + serviceName;
            if (!serviceToNodeId.containsKey(key)) {
                String nodeId = "dep_" + idx++;
                serviceToNodeId.put(key, nodeId);
                sb.append("    ").append(nodeId).append("[\"[").append(d[0]).append("] ").append(serviceName).append("\"]\n");
                sb.append("    ").append(sysId).append(" --> ").append(nodeId).append("\n");
            }
        }

        // 配置中有但边界点未覆盖的依赖类型（DB / Redis）
        if (!cfg.get("DB").isEmpty() && deps.stream().noneMatch(d -> "DB".equals(d[0]))) {
            sb.append("    db_node[\"[DB] MySQL\"]\n");
            sb.append("    ").append(sysId).append(" --> db_node\n");
        }
        if (!cfg.get("REDIS").isEmpty()) {
            sb.append("    redis_node[\"[CACHE] Redis\"]\n");
            sb.append("    ").append(sysId).append(" --> redis_node\n");
        }
        if (!cfg.get("KAFKA").isEmpty() && deps.stream().noneMatch(d -> "MQ".equals(d[0]))) {
            sb.append("    kafka_node[\"[MQ] Kafka\"]\n");
            sb.append("    ").append(sysId).append(" --> kafka_node\n");
        }
        sb.append("```\n\n");

        // 地址速查表（附在图下，避免 Mermaid 节点放长 URL）
        sb.append("**地址速查：**\n\n");
        sb.append("| 类型 | 配置项 | 实际地址 |\n|------|--------|----------|\n");
        appendAddrRows(sb, "DB", cfg.get("DB"));
        appendAddrRows(sb, "Redis", cfg.get("REDIS"));
        appendAddrRows(sb, "Kafka", cfg.get("KAFKA"));
        appendAddrRows(sb, "RocketMQ", cfg.get("ROCKETMQ"));
        appendAddrRows(sb, "gRPC", cfg.get("GRPC"));
        appendAddrRows(sb, "HTTP", cfg.get("HTTP"));
        sb.append("\n");

        return sb.toString();
    }

    private void appendAddrRows(StringBuilder sb, String type, List<String> entries) {
        for (String entry : entries) {
            sb.append("| ").append(type).append(" | ").append(entry).append(" |\n");
        }
    }

    /**
     * 从 BoundaryEntity.context 中提取可读的服务名
     * 支持 RestTemplate URL、Feign 客户端名、MQ Topic、gRPC 服务名
     */
    private String extractServiceName(String boundaryType, String context) {
        if (context == null || context.isBlank()) return boundaryType;
        String[] lines = context.split("\n");
        String firstLine = lines[0].trim();

        // 1. 优先从 context 中找 URL 行提取 host
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("URL:") || t.startsWith("url:")) {
                return extractHostFromUrl(t.substring(4).trim());
            }
            if (t.contains("http://") || t.contains("https://")) {
                int start = t.indexOf("http");
                return extractHostFromUrl(t.substring(start).split("\\s")[0]);
            }
        }

        // 2. Feign 调用：提取接口类名（去掉包路径和方法名）
        if (firstLine.toLowerCase().contains("feign")) {
            int colon = firstLine.indexOf(':');
            if (colon > 0) {
                String classPart = firstLine.substring(colon + 1).trim();
                String[] segments = classPart.split("\\.");
                // fullMethod = pkg.ClassName:methodName(...)，取类名部分
                for (int i = segments.length - 1; i >= 0; i--) {
                    String seg = segments[i].split("\\(")[0];
                    if (!seg.isEmpty() && Character.isUpperCase(seg.charAt(0))) {
                        return seg;
                    }
                }
            }
        }

        // 3. RestTemplate：context 第一行格式为 "RestTemplate: pkg.RestTemplate.method"
        //    尝试从后续行找 URL，否则标记为 HTTP Service
        if (firstLine.toLowerCase().contains("resttemplate")) {
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("URL:") || t.startsWith("url:")) return extractHostFromUrl(t.substring(4).trim());
                if (t.contains("http://") || t.contains("https://")) return extractHostFromUrl(t.substring(t.indexOf("http")).split("\\s")[0]);
            }
            return "HTTP Service";
        }

        // 4. MQ：尝试提取 Topic 名
        if ("MQ".equals(boundaryType)) {
            for (String line : lines) {
                String t = line.trim().toLowerCase();
                if (t.contains("topic") || t.contains("destination")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) return line.substring(colon + 1).trim();
                }
            }
            return "Message Queue";
        }

        // 4. gRPC：提取 stub 类名
        if ("GRPC".equals(boundaryType)) {
            String[] segments = firstLine.split("[.:]");
            for (int i = segments.length - 1; i >= 0; i--) {
                String seg = segments[i].split("\\(")[0].trim();
                if (!seg.isEmpty() && Character.isUpperCase(seg.charAt(0))) return seg;
            }
        }

        // 5. 降级：去掉包路径，只保留类名
        int lastDot = firstLine.lastIndexOf('.');
        int lastColon = firstLine.lastIndexOf(':');
        int cut = Math.max(lastDot, lastColon);
        if (cut > 0 && cut < firstLine.length() - 1) {
            String simple = firstLine.substring(cut + 1).split("\\(")[0].trim();
            if (!simple.isEmpty()) return simple;
        }
        return firstLine.length() > 40 ? firstLine.substring(0, 40) : firstLine;
    }

    private String extractHostFromUrl(String url) {
        try {
            String host = url;
            if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);
            // 去掉常见环境变量占位符 ${...}
            host = host.replaceAll("\\$\\{[^}]+\\}", "").replaceAll("^[^a-zA-Z0-9]+", "").trim();
            return host.isEmpty() ? url : host;
        } catch (Exception e) {
            return url.length() > 40 ? url.substring(0, 40) : url;
        }
    }

    // ========== 项目结构：代码直出 ==========

    /**
     * 生成项目结构章节：按层级（Controller/Service/Mapper/Entity/Config/工具）分组，列出每个类的职责
     */
    private String generateProjectStructure(Long repoId, List<ApiEndpointEntity> allEndpoints) {
        List<ChunkEntity> chunks = chunkRepo.findByRepoId(repoId);
        if (chunks.isEmpty()) return "";

        // ── 按 className 分组 ──
        Map<String, List<ChunkEntity>> byClass = new LinkedHashMap<>();
        for (ChunkEntity c : chunks) {
            if (c.getClassName() != null && !c.getClassName().isBlank()) {
                byClass.computeIfAbsent(c.getClassName(), k -> new ArrayList<>()).add(c);
            }
        }
        if (byClass.isEmpty()) return "";

        // ── 公共包前缀 ──
        String commonPrefix = findCommonPackagePrefix(byClass.keySet());

        // ── Controller 的路由索引 ──
        Map<String, List<String>> classToRoutes = new LinkedHashMap<>();
        for (ApiEndpointEntity ep : allEndpoints) {
            if (ep.getClassName() != null) {
                String route = (ep.getHttpMethod() != null ? ep.getHttpMethod() + " " : "")
                        + (ep.getUrlPath() != null ? ep.getUrlPath() : ep.getEndpointType());
                classToRoutes.computeIfAbsent(ep.getClassName(), k -> new ArrayList<>()).add(route);
            }
        }

        // ── 分层分组 ──
        // 固定顺序的层
        String[] layerOrder = {
            "接口层 (Controller)",
            "消息消费 (Consumer/Listener)",
            "定时任务 (Scheduled)",
            "业务逻辑层 (Service)",
            "数据访问层 (Mapper/Repository)",
            "实体 & DTO",
            "配置 & 工具",
            "其他"
        };
        Map<String, List<ClassSummary>> layerGroups = new LinkedHashMap<>();
        for (String l : layerOrder) layerGroups.put(l, new ArrayList<>());

        for (Map.Entry<String, List<ChunkEntity>> entry : byClass.entrySet()) {
            String className = entry.getKey();
            String shortName = shortClass(className);
            List<ChunkEntity> methods = entry.getValue();
            int methodCount = (int) methods.stream()
                    .filter(c -> c.getMethodName() != null
                            && !"<init>".equals(c.getMethodName())
                            && !"<clinit>".equals(c.getMethodName()))
                    .count();
            String type = detectClassType(shortName, methods);
            List<String> routes = classToRoutes.getOrDefault(className, List.of());
            String layer = classTypeToLayer(type);
            layerGroups.get(layer).add(new ClassSummary(shortName, type, methodCount, routes));
        }

        // ── 构建 Markdown ──
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目结构\n\n");
        if (!commonPrefix.isEmpty()) {
            sb.append("> 公共包前缀：`").append(commonPrefix).append("`\n\n");
        }

        for (String layer : layerOrder) {
            List<ClassSummary> classes = layerGroups.get(layer);
            if (classes.isEmpty()) continue;

            // 按类型内部排序：先有路由/方法的，再按名字
            classes.sort(Comparator.comparingInt((ClassSummary c) -> c.routes().isEmpty() ? 1 : 0)
                    .thenComparing(ClassSummary::shortName));

            sb.append("### ").append(layer).append(" (").append(classes.size()).append(" 个类)\n\n");

            if (layer.startsWith("接口层")) {
                sb.append("| 类名 | 暴露路由 | 职责 |\n|------|---------|------|\n");
                for (ClassSummary cs : classes) {
                    String routeStr = cs.routes().isEmpty() ? "-"
                            : cs.routes().stream().limit(5).map(r -> "`" + r + "`").collect(Collectors.joining("<br>"))
                              + (cs.routes().size() > 5 ? "<br>_…共 " + cs.routes().size() + " 个_" : "");
                    sb.append("| `").append(cs.shortName()).append("` | ")
                      .append(routeStr).append(" | ")
                      .append(inferClassRole(cs.shortName(), cs.type())).append(" |\n");
                }
            } else if (layer.startsWith("实体")) {
                sb.append("| 类名 | 类型 | 职责 |\n|------|------|------|\n");
                for (ClassSummary cs : classes) {
                    sb.append("| `").append(cs.shortName()).append("` | ")
                      .append(cs.type()).append(" | ")
                      .append(inferClassRole(cs.shortName(), cs.type())).append(" |\n");
                }
            } else {
                sb.append("| 类名 | 方法数 | 职责 |\n|------|--------|------|\n");
                for (ClassSummary cs : classes) {
                    sb.append("| `").append(cs.shortName()).append("` | ")
                      .append(cs.methodCount() > 0 ? String.valueOf(cs.methodCount()) : "-").append(" | ")
                      .append(inferClassRole(cs.shortName(), cs.type())).append(" |\n");
                }
            }
            sb.append("\n");
        }

        // 统计摘要
        int total = byClass.size();
        sb.append("> 共 ").append(total).append(" 个类，").append(chunks.size()).append(" 个方法\n");

        return sb.toString();
    }

    private record ClassSummary(String shortName, String type, int methodCount, List<String> routes) {}

    /** 根据类名后缀 + 方法注解判断类的类型 */
    private String detectClassType(String shortName, List<ChunkEntity> methods) {
        String lower = shortName.toLowerCase();
        if (lower.endsWith("controller"))                              return "Controller";
        if (lower.endsWith("restcontroller"))                          return "Controller";
        if (lower.endsWith("serviceimpl"))                             return "ServiceImpl";
        if (lower.endsWith("service"))                                 return "Service";
        if (lower.endsWith("mapper"))                                  return "Mapper";
        if (lower.endsWith("repository") || lower.endsWith("repo"))   return "Repository";
        if (lower.endsWith("dao"))                                     return "DAO";
        if (lower.endsWith("entity"))                                  return "Entity";
        if (lower.endsWith("dto"))                                     return "DTO";
        if (lower.endsWith("vo"))                                      return "VO";
        if (lower.endsWith("bo"))                                      return "BO";
        if (lower.endsWith("request"))                                 return "Request";
        if (lower.endsWith("response"))                                return "Response";
        if (lower.endsWith("configuration") || lower.endsWith("config")) return "Config";
        if (lower.endsWith("properties"))                              return "Config";
        if (lower.endsWith("utils") || lower.endsWith("util"))        return "Utils";
        if (lower.endsWith("helper"))                                  return "Utils";
        if (lower.endsWith("handler"))                                 return "Handler";
        if (lower.endsWith("interceptor"))                             return "Interceptor";
        if (lower.endsWith("filter"))                                  return "Filter";
        if (lower.endsWith("aspect"))                                  return "Aspect";
        if (lower.endsWith("consumer"))                                return "MQ Consumer";
        if (lower.endsWith("listener"))                                return "Listener";
        if (lower.endsWith("producer"))                                return "Producer";
        if (lower.endsWith("scheduler") || lower.endsWith("task") || lower.endsWith("job")) return "Scheduled";
        if (lower.endsWith("client") || lower.endsWith("feignclient")) return "FeignClient";
        if (lower.endsWith("stub"))                                    return "gRPC Stub";
        if (lower.endsWith("converter") || lower.endsWith("serializer")) return "Converter";
        if (lower.endsWith("exception"))                               return "Exception";
        if (lower.endsWith("enums") || lower.endsWith("enum"))        return "Enum";
        if (lower.endsWith("constants") || lower.endsWith("constant") || lower.endsWith("consts")) return "Constants";

        // 通过方法注解推断
        for (ChunkEntity method : methods) {
            String ann = method.getAnnotations();
            if (ann == null) continue;
            if (ann.contains("GetMapping") || ann.contains("PostMapping") || ann.contains("RequestMapping")
                    || ann.contains("PutMapping") || ann.contains("DeleteMapping")) return "Controller";
            if (ann.contains("KafkaListener") || ann.contains("RocketMQMessageListener")
                    || ann.contains("RabbitListener")) return "MQ Consumer";
            if (ann.contains("Scheduled")) return "Scheduled";
            if (ann.contains("GrpcMethod")) return "gRPC Service";
        }
        return "Class";
    }

    /** 类型 → 层级分组名 */
    private String classTypeToLayer(String type) {
        return switch (type) {
            case "Controller" -> "接口层 (Controller)";
            case "MQ Consumer", "Listener", "Producer" -> "消息消费 (Consumer/Listener)";
            case "Scheduled", "Task", "Job" -> "定时任务 (Scheduled)";
            case "Service", "ServiceImpl", "gRPC Service" -> "业务逻辑层 (Service)";
            case "Mapper", "Repository", "DAO" -> "数据访问层 (Mapper/Repository)";
            case "Entity", "DTO", "VO", "BO", "Request", "Response" -> "实体 & DTO";
            case "Config", "Utils", "Helper", "Handler", "Interceptor", "Filter",
                 "Aspect", "FeignClient", "gRPC Stub", "Converter",
                 "Exception", "Enum", "Constants" -> "配置 & 工具";
            default -> "其他";
        };
    }

    /** 根据类名推断一句话职责说明 */
    private String inferClassRole(String shortName, String type) {
        // 去掉类型后缀，得到业务域名
        String domain = shortName.replaceAll(
                "(?i)(Controller|ServiceImpl|Service|Mapper|Repository|Repo|Dao|Entity|Dto|Vo|Bo"
                + "|Request|Response|Configuration|Config|Properties|Utils|Util|Helper"
                + "|Handler|Interceptor|Filter|Aspect|Consumer|Producer|Listener"
                + "|Scheduler|Task|Job|Client|FeignClient|Stub|Converter|Serializer"
                + "|Exception|Enums|Enum|Constants|Constant|Consts)$", "").trim();
        if (domain.isEmpty()) domain = shortName;
        return switch (type) {
            case "Controller"   -> domain + " 相关接口入口";
            case "Service"      -> domain + " 业务接口定义";
            case "ServiceImpl"  -> domain + " 业务逻辑实现";
            case "Mapper", "Repository", "DAO" -> domain + " 数据访问";
            case "Entity"       -> domain + " 数据库实体";
            case "DTO", "Request" -> domain + " 传输对象";
            case "VO", "Response" -> domain + " 视图/响应对象";
            case "Config", "Properties" -> domain + " 配置";
            case "Utils", "Helper" -> domain + " 工具类";
            case "FeignClient"  -> domain + " 远程调用客户端";
            case "MQ Consumer", "Listener" -> domain + " 消息消费";
            case "Producer"     -> domain + " 消息发送";
            case "Scheduled", "Task", "Job" -> domain + " 定时任务";
            case "Handler"      -> domain + " 处理器";
            case "Exception"    -> domain + " 异常定义";
            case "Enum"         -> domain + " 枚举定义";
            case "Constants"    -> domain + " 常量定义";
            case "Aspect"       -> domain + " AOP 切面";
            case "gRPC Stub", "gRPC Service" -> domain + " gRPC 调用";
            case "Converter"    -> domain + " 类型转换";
            default             -> domain.equals(shortName) ? "-" : domain + " 相关";
        };
    }

    /** 找所有类名的公共包前缀（去掉类名本身）*/
    private String findCommonPackagePrefix(Collection<String> classNames) {
        if (classNames.isEmpty()) return "";
        List<String[]> split = classNames.stream()
                .map(n -> n.split("\\."))
                .collect(Collectors.toList());
        String[] first = split.get(0);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < first.length - 1; i++) {
            final String seg = first[i];
            final int idx = i;
            boolean allHave = split.stream().allMatch(parts -> parts.length > idx && parts[idx].equals(seg));
            if (!allHave) break;
            if (prefix.length() > 0) prefix.append(".");
            prefix.append(seg);
        }
        return prefix.toString();
    }

    // ========== END 项目结构 ==========

    /**
     * 第 2 轮（原）：单个接口的完整档案
     * 原则：所有内容必须来自源码，找不到的内容输出异常标记，绝不推断
     */
    private String generateEndpointDoc(Long repoId, RepositoryEntity repo, ApiEndpointEntity ep) {
        String httpInfo = ep.getHttpMethod() != null ? ep.getHttpMethod() + " " : "";
        String urlInfo = ep.getUrlPath() != null ? ep.getUrlPath() : "";
        String header = "### " + shortClass(ep.getClassName()) + " · " + httpInfo + urlInfo + "\n\n";

        // ===== 加载调用链源码 =====
        StringBuilder sourceContext = new StringBuilder();
        boolean sourceLoaded = false;
        try {
            var tree = callGraphEngine.expandCallTree(repoId, ep.getFullMethod(), 10);
            if (tree.root() != null) {
                List<String> chainMethods = new ArrayList<>();
                collectMethods(tree.root(), chainMethods, new HashSet<>());
                for (String m : chainMethods) {
                    String src = callGraphEngine.getMethodSource(repoId, m);
                    if (src != null) {
                        sourceContext.append("// === ").append(shortMethod(m)).append(" ===\n");
                        sourceContext.append(src).append("\n\n");
                        sourceLoaded = true;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[文档生成] 调用链加载异常: {}", ep.getFullMethod(), e);
        }

        // 源码未加载到 → 直接返回异常标记，不调 AI
        if (!sourceLoaded) {
            return header
                    + "> 【异常点】 **异常点**：未能从源码中加载该接口的调用链（`"
                    + ep.getFullMethod() + "`），无法生成文档。"
                    + "请确认源码文件存在且已完成静态分析后重新生成。\n";
        }

        // ===== 加载入参实体类 =====
        StringBuilder paramInfo = new StringBuilder();
        boolean paramLoaded = false;
        try {
            var detail = callGraphEngine.getMethodSourceDetail(repoId, ep.getFullMethod(), ep.getFullMethod());
            if (detail != null && detail.paramClasses() != null && !detail.paramClasses().isEmpty()) {
                for (var pc : detail.paramClasses()) {
                    paramInfo.append("实体类: ").append(pc.shortName()).append("\n");
                    for (String field : pc.fields()) {
                        paramInfo.append("  ").append(field).append("\n");
                    }
                }
                paramLoaded = true;
            }
        } catch (Exception e) { /* skip */ }
        String paramSection = paramLoaded
                ? paramInfo.toString()
                : "【异常点】 异常点：未能从源码中提取入参实体类字段，入参说明章节将标记为待补充。";

        // ===== 加载边界点 =====
        StringBuilder boundaryInfo = new StringBuilder();
        boolean boundaryLoaded = false;
        List<BoundaryEntity> boundaries = new ArrayList<>(boundaryRepo.findByRepoIdAndFullMethod(repoId, ep.getFullMethod()));
        try {
            var tree = callGraphEngine.expandCallTree(repoId, ep.getFullMethod(), 10);
            if (tree.root() != null) {
                List<String> chainMethods = new ArrayList<>();
                collectMethods(tree.root(), chainMethods, new HashSet<>());
                for (String m : chainMethods) {
                    boundaries.addAll(boundaryRepo.findByRepoIdAndFullMethod(repoId, m));
                }
            }
        } catch (Exception e) { /* skip */ }

        for (BoundaryEntity b : boundaries) {
            boundaryInfo.append("- [").append(b.getBoundaryType()).append("] ");
            if (b.getContext() != null) boundaryInfo.append(b.getContext().split("\n")[0]);
            boundaryInfo.append(" (见 `").append(shortMethod(b.getFullMethod())).append("`)");
            boundaryInfo.append("\n");
            boundaryLoaded = true;
        }
        String boundarySection = boundaryLoaded
                ? boundaryInfo.toString()
                : "源码中未检测到该接口的外部依赖（HTTP/gRPC/MQ/DB 边界点）。";

        // ===== 构建 prompt，严格基于已加载的源码内容 =====
        String prompt = "## 接口信息\n"
                + "- 类型：" + ep.getEndpointType() + "\n"
                + "- 方法：" + httpInfo + urlInfo + "\n"
                + "- 类名：" + shortClass(ep.getClassName()) + "\n\n"
                + "## 调用链源码（这是唯一可信来源，所有内容必须来自此处）\n"
                + sourceContext + "\n"
                + "## 入参实体类\n"
                + paramSection + "\n\n"
                + "## 边界点（源码静态分析结果）\n"
                + boundarySection + "\n\n"
                + "## 生成规则（必须严格遵守）\n"
                + "- 所有内容只能来自上方提供的源码，不得推断、补全或引入任何源码中不存在的内容\n"
                + "- 如果某项内容在源码中找不到依据，该项输出：`【异常点】 异常点：源码中未找到[具体内容]，需补充后重新生成`\n"
                + "- 禁止使用\"可能\"、\"通常\"、\"建议\"、\"一般来说\"等推断性措辞\n"
                + "- 用 ### 作为标题级别\n\n"
                + "## 请生成以下内容\n\n"
                + "### 功能描述\n"
                + "用 1-2 句话描述这个接口的业务功能。只能根据源码中的方法名、注释、日志文字描述，不得推断。\n\n"
                + "### 业务流程\n"
                + "根据调用链源码的实际执行顺序，用 Mermaid sequenceDiagram 画出流程。"
                + "参与者只能使用源码中出现的类名（可缩短为简单类名），不得替换为推断的业务系统名称。\n\n"
                + "### 入参说明\n"
                + (paramLoaded
                    ? "表格：字段名 | 类型 | 必填 | 限制 | 说明\n只列出入参实体类中明确存在的字段，限制只填写源码中有注解或 if 判断明确约束的内容，没有就填 - 。\n"
                    : "`【异常点】 异常点：未能提取入参实体类，此章节待补充。`\n")
                + "\n### 校验规则\n"
                + "只列出源码中明确存在的校验逻辑（@NotNull/@Size 等注解、if 判断 + throw、提前 return）。"
                + "每条标注来源 (见 `类名.方法名`)。源码中找不到校验逻辑时输出：`源码中未发现校验逻辑`。\n\n"
                + "### 异常场景\n"
                + "表格：场景 | 触发条件 | 错误提示 | 来源\n"
                + "只从源码中的 throw 语句提取，有几条写几条，不得补充推断的异常场景。找不到时输出：`源码中未发现显式异常抛出`。\n\n"
                + "### 外部依赖\n"
                + (boundaryLoaded
                    ? "表格：依赖方 | 调用方式 | 用途\n只列出边界点中出现的依赖，依赖方名称使用边界点中的原始描述，不得推断服务名称。\n"
                    : "`源码中未检测到外部依赖。`\n")
                + "\n### 数据变更\n"
                + "只列出源码中明确出现的数据库写操作（INSERT/UPDATE/DELETE），标注来源。找不到时输出：`源码中未发现数据库写操作`。\n";

        return header + claudeClient.chat(DOC_SYSTEM_PROMPT, List.of(Map.of("role", "user", "content", prompt)));
    }

    /**
     * 第 3 轮：状态机 + 错误码 + 风险点
     * 原则：所有内容来自源码静态分析结果，为空时输出异常标记，不让 AI 推断
     */
    private String generateRound3(Long repoId, RepositoryEntity repo, List<BoundaryEntity> allBoundaries) {
        // ===== 枚举/状态信息：从 profile 中提取 =====
        StringBuilder enumInfo = new StringBuilder();
        if (repo.getProfile() != null) {
            String profile = repo.getProfile();
            if (profile.contains("业务术语:")) {
                enumInfo.append(profile.substring(profile.indexOf("业务术语:") + 5));
            }
        }
        boolean hasEnumInfo = enumInfo.length() > 0;

        // ===== 异常信息：从 BoundaryEntity 提取，保留完整 context =====
        StringBuilder exceptionInfo = new StringBuilder();
        Map<String, Integer> exceptionCounts = new LinkedHashMap<>();
        for (BoundaryEntity b : allBoundaries) {
            if ("EXCEPTION".equals(b.getBoundaryType()) && b.getContext() != null) {
                String firstLine = b.getContext().split("\n")[0];
                exceptionCounts.merge(firstLine, 1, Integer::sum);
            }
        }
        for (var entry : exceptionCounts.entrySet()) {
            exceptionInfo.append("- ").append(entry.getKey()).append(" (").append(entry.getValue()).append("次)\n");
        }
        boolean hasExceptionInfo = exceptionInfo.length() > 0;

        // ===== 配置信息（脱敏） =====
        StringBuilder configInfo = new StringBuilder();
        var configs = repoConfigRepo.findByRepoId(repoId);
        if (configs != null) {
            configs.stream()
                    .filter(c -> c.getConfigValue() != null && !c.getConfigValue().isBlank())
                    .filter(c -> {
                        String k = c.getConfigKey().toLowerCase();
                        return k.contains("timeout") || k.contains("pool") || k.contains("retry")
                                || k.contains("max") || k.contains("min") || k.contains("size");
                    })
                    .limit(20)
                    .forEach(c -> configInfo.append("- ").append(c.getConfigKey()).append(" = ").append(c.getConfigValue()).append("\n"));
        }
        boolean hasConfigInfo = configInfo.length() > 0;

        // ===== 构建 prompt，缺失内容直接标记异常点，不要求 AI 推断 =====
        String prompt = "## 枚举/状态信息（源码静态提取）\n"
                + (hasEnumInfo ? enumInfo : "【异常点】 异常点：未从源码中提取到枚举/状态信息，状态流转章节无法生成。") + "\n\n"
                + "## 异常统计（源码静态提取）\n"
                + (hasExceptionInfo ? exceptionInfo : "源码中未检测到显式异常抛出点。") + "\n\n"
                + "## 性能相关配置（源码静态提取）\n"
                + (hasConfigInfo ? configInfo : "源码中未检测到超时/连接池/重试相关配置。") + "\n\n"
                + "## 生成规则（必须严格遵守）\n"
                + "- 所有内容只能来自上方提供的数据，不得推断、补全\n"
                + "- 标注了【异常点】的章节，直接输出该异常标记，不生成任何内容\n"
                + "- 禁止根据项目类型推断状态、错误码或风险点\n\n"
                + "## 请生成以下内容\n\n"
                + "### 状态流转\n"
                + (hasEnumInfo
                    ? "只根据上方枚举信息，找出明确表示状态的枚举，用 Mermaid stateDiagram-v2 画出状态流转图。"
                    + "若无法从枚举信息中确定流转关系，用表格列出枚举值，不要推断流转箭头。\n"
                    : "【异常点】未能提取枚举/状态信息，此章节待补充。\n")
                + "\n### 错误码字典\n"
                + (hasExceptionInfo
                    ? "只整理上方异常统计中出现的错误，表格：异常类型 | 出现次数 | 描述（描述只填写异常类名本身的语义，不推断业务含义）\n"
                    : "源码中未检测到显式异常抛出，此章节无内容。\n")
                + "\n### 注意事项\n"
                + (hasConfigInfo
                    ? "只根据上方配置项，列出实际存在的配置值，不评价其是否合理，不补充未配置项的建议。\n"
                    : "源码中未检测到性能相关配置，此章节无内容。\n");

        return claudeClient.chat(DOC_SYSTEM_PROMPT, List.of(Map.of("role", "user", "content", prompt)));
    }

    /**
     * 静态生成的章节（不调 AI）
     */
    private String generateStaticSections(Long repoId, RepositoryEntity repo) {
        StringBuilder doc = new StringBuilder();

        // 关键配置项（脱敏）
        var configs = repoConfigRepo.findByRepoId(repoId);
        if (configs != null && !configs.isEmpty()) {
            List<RepoConfigEntity> keyConfigs = configs.stream()
                    .filter(c -> c.getConfigValue() != null && !c.getConfigValue().isBlank())
                    .filter(c -> {
                        String k = c.getConfigKey().toLowerCase();
                        return k.contains("url") || k.contains("host") || k.contains("port")
                                || k.contains("topic") || k.contains("timeout") || k.contains("pool")
                                || k.contains("datasource") || k.contains("redis") || k.contains("kafka")
                                || k.contains("grpc") || k.contains("feign")
                                || k.contains("server.port") || k.contains("spring.application");
                    })
                    .limit(30)
                    .collect(Collectors.toList());
            if (!keyConfigs.isEmpty()) {
                doc.append("## 关键配置项\n\n");
                doc.append("| 配置项 | 值 |\n|--------|-----|\n");
                for (var c : keyConfigs) {
                    String val = desensitize(c.getConfigKey(), c.getConfigValue());
                    doc.append("| ").append(c.getConfigKey()).append(" | ").append(val).append(" |\n");
                }
                doc.append("\n");
            }
        }

        // 统计
        int methodCount = chunkRepo.findByRepoId(repoId).size();
        int callCount = 0; // 避免大查询
        List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);
        List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);
        doc.append("## 统计\n\n");
        doc.append("| 指标 | 数量 |\n|------|------|\n");
        doc.append("| 方法总数 | ").append(methodCount).append(" |\n");
        doc.append("| 接口数 | ").append(endpoints.size()).append(" |\n");
        doc.append("| 边界点 | ").append(allBoundaries.size()).append(" |\n");
        doc.append("\n");

        return doc.toString();
    }

    /** 配置值脱敏 */
    private String desensitize(String key, String value) {
        if (value == null || value.isBlank()) return "";
        String keyLower = key.toLowerCase();
        if (keyLower.contains("password") || keyLower.contains("secret")
                || keyLower.contains("token") || keyLower.contains("api-key")
                || keyLower.contains("apikey") || keyLower.contains("credential")) {
            return "******";
        }
        if (keyLower.contains("username") || keyLower.contains("user-name")) {
            return value.length() > 2 ? value.substring(0, 2) + "***" : "***";
        }
        if (keyLower.contains("url") || keyLower.contains("base-url") || keyLower.contains("endpoint")) {
            value = value.replaceAll("//[^/:]+", "//***");
            return value.length() > 80 ? value.substring(0, 80) + "..." : value;
        }
        if (keyLower.contains("host") || keyLower.contains("server") || keyLower.contains("broker")) {
            return value.replaceAll("[\\w.-]+\\.(cn|com|net|org|io|local)(:\\d+)?", "***$2")
                        .replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "***");
        }
        value = value.replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "***");
        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }

    // ========== 原有的静态文档生成方法保留 ==========

    /**
     * 生成产品视角文档（静态，不调 AI）
     */
    public String generateProductDoc(Long repoId, String entryMethod) {
        // ... 保留原有逻辑
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 15);
        if (tree.root() == null) return "接口未找到";
        return codeGenerator.generate(repoId, entryMethod);
    }

    /**
     * 生成研发视角文档（静态，不调 AI）
     */
    public String generateDevDoc(Long repoId, String entryMethod) {
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 15);
        if (tree.root() == null) return "接口未找到";
        return codeGenerator.generate(repoId, entryMethod);
    }

    // ========== 工具方法 ==========

    private void collectMethods(CallGraphEngine.CallTreeNodeDTO node, List<String> result, Set<String> visited) {
        if (node == null || visited.contains(node.fullMethod())) return;
        visited.add(node.fullMethod());
        result.add(node.fullMethod());
        if (node.children() != null) {
            for (var child : node.children()) collectMethods(child, result, visited);
        }
    }

    private String shortClass(String className) {
        return className != null && className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : (className != null ? className : "");
    }

    private String shortMethod(String fullMethod) {
        if (fullMethod == null) return "";
        String cls = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(0, fullMethod.lastIndexOf(':')) : fullMethod;
        String method = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(fullMethod.lastIndexOf(':') + 1) : "";
        int paren = method.indexOf('(');
        if (paren > 0) method = method.substring(0, paren);
        return shortClass(cls) + "." + method;
    }

    private String shortMethodName(String fullMethod) {
        if (fullMethod == null) return "";
        String method = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(fullMethod.lastIndexOf(':') + 1) : fullMethod;
        int paren = method.indexOf('(');
        return paren > 0 ? method.substring(0, paren) : method;
    }
}
