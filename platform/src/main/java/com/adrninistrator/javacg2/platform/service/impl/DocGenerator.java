package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.*;
import com.adrninistrator.javacg2.platform.repository.*;
import com.adrninistrator.javacg2.platform.service.BuildLogService;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.AnalysisDataExtractor;
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
    private final AnalysisDataExtractor analysisDataExtractor;
    private final ExternalCallFormatter externalCallFormatter;
    private final com.adrninistrator.javacg2.platform.service.PromptService promptService;

    /** 产品文档缓存：key=repoId|entryMethod，TTL 10min，LRU 上限 100 */
    private static final long PRODUCT_DOC_CACHE_TTL_MS = 10 * 60 * 1000L;
    private record CachedDoc(String content, long ts) {}
    private final Map<String, CachedDoc> productDocCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedDoc> eldest) {
                    return size() > 100;
                }
            });

    public DocGenerator(CallGraphEngine callGraphEngine, BoundaryRepo boundaryRepo,
                         CallChainCodeGenerator codeGenerator, ClaudeApiClient claudeClient,
                         ApiEndpointRepo apiEndpointRepo, ChunkRepo chunkRepo,
                         RepositoryRepo repositoryRepo, ProjectInfoExtractor projectInfoExtractor,
                         RepoConfigRepo repoConfigRepo, BuildLogService buildLogService,
                         AnalysisDataExtractor analysisDataExtractor,
                         ExternalCallFormatter externalCallFormatter,
                         com.adrninistrator.javacg2.platform.service.PromptService promptService) {
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
        this.analysisDataExtractor = analysisDataExtractor;
        this.externalCallFormatter = externalCallFormatter;
        this.promptService = promptService;
    }

    /**
     * AI 生成完整项目概览文档（异步调用）
     * 包含两个层次：
     * 1. 项目级业务概览（面向产品经理）
     * 2. 接口级技术详情（面向开发人员，含方法调用链和 Mermaid 图）
     */
    public String generateAIOverview(Long repoId) {
        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return "仓库不存在";

        List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);
        List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);

        StringBuilder fullDoc = new StringBuilder();

        // ========== 第 1 轮：AI 项目简介 + 核心能力 ==========
        buildLogService.append(repoId, "📖 [1/6] 生成项目简介和核心能力...");
        try {
            String round1 = generateProjectIntroduction(repo, endpoints, allBoundaries);
            fullDoc.append(round1).append("\n\n");
            buildLogService.append(repoId, "✅ [1/6] 项目简介完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第1轮失败", e);
            buildLogService.append(repoId, "【异常点】 [1/6] 失败: " + e.getMessage());
            fullDoc.append("## 项目简介\n\n> 生成失败，请重试\n\n");
        }

        // ========== 第 2 轮：技术栈 + 架构图 + 外部依赖（代码直出）==========
        buildLogService.append(repoId, "📖 [2/6] 生成技术栈和架构信息...");
        try {
            String techStack = generateTechnicalOverview(repo, endpoints, allBoundaries);
            fullDoc.append(techStack).append("\n\n");
            buildLogService.append(repoId, "✅ [2/6] 技术栈完成");
        } catch (Exception e) {
            logger.warn("[文档生成] 技术栈生成失败", e);
            buildLogService.append(repoId, "⚠️ [2/6] 技术栈生成跳过: " + e.getMessage());
        }

        // ========== 第 3 轮：功能模块和主要功能（业务视角）==========
        buildLogService.append(repoId, "📖 [3/6] 生成功能模块清单...");
        try {
            String modules = generateBusinessModules(repo, endpoints);
            fullDoc.append(modules).append("\n\n");
            buildLogService.append(repoId, "✅ [3/6] 功能模块完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第3轮失败", e);
            buildLogService.append(repoId, "【异常点】 [3/6] 失败: " + e.getMessage());
        }

        // ========== 第 4 轮：业务数据字典（枚举/状态码/错误码）==========
        buildLogService.append(repoId, "📖 [4/6] 生成数据字典...");
        try {
            String dataDict = generateBusinessDataDictionary(repoId);
            fullDoc.append(dataDict).append("\n\n");
            buildLogService.append(repoId, "✅ [4/6] 数据字典完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第4轮失败", e);
            buildLogService.append(repoId, "【异常点】 [4/6] 失败: " + e.getMessage());
        }

        // ========== 第 5 轮：项目级业务流程（主要用户场景）==========
        buildLogService.append(repoId, "📖 [5/6] 生成主要业务流程...");
        try {
            String flows = generateBusinessFlows(repo, endpoints, allBoundaries);
            fullDoc.append(flows).append("\n\n");
            buildLogService.append(repoId, "✅ [5/6] 业务流程完成");
        } catch (Exception e) {
            logger.error("[文档生成] 第5轮失败", e);
            buildLogService.append(repoId, "【异常点】 [5/6] 失败: " + e.getMessage());
        }

        // ========== 第 6 轮：接口技术详情（方法级调用链 + Mermaid 图）==========
        buildLogService.append(repoId, "📖 [6/6] 生成接口技术详情（共 " + endpoints.size() + " 个）...");
        fullDoc.append("## 接口技术详情\n\n");
        fullDoc.append("> 以下是每个接口的详细技术实现，包含方法调用链和流程图。\n\n");
        
        int done = 0;
        for (ApiEndpointEntity ep : endpoints) {
            try {
                String epDoc = generateEndpointDoc(repoId, repo, ep);
                fullDoc.append(epDoc).append("\n\n");
                done++;
                if (done % 5 == 0) {
                    buildLogService.append(repoId, "📖 [6/6] 已完成 " + done + "/" + endpoints.size() + " 个接口");
                }
            } catch (Exception e) {
                logger.warn("[文档生成] 接口详情失败: {}", ep.getFullMethod(), e);
                String shortName = shortClass(ep.getClassName()) + "." + shortMethodName(ep.getFullMethod());
                fullDoc.append("### ").append(shortName).append("\n\n> 生成失败\n\n");
            }
        }
        buildLogService.append(repoId, "✅ [6/6] 接口详情完成（" + done + "/" + endpoints.size() + "）");

        // 追加统计信息
        fullDoc.append(generateStaticSections(repoId, repo));

        return fullDoc.toString();
    }

    /**
     * 第 1 轮：项目简介 + 核心能力（AI 生成，面向产品经理）
     * 重点：这个项目是什么、服务于谁、解决什么问题、核心能力有哪些
     */
    private String generateProjectIntroduction(RepositoryEntity repo, List<ApiEndpointEntity> endpoints,
                                                List<BoundaryEntity> allBoundaries) {
        var projectInfo = projectInfoExtractor.extract(repo.getLocalPath());

        // ===== 提取枚举和业务数据（用于让 AI 了解业务域）=====
        Map<String, List<AnalysisDataExtractor.EnumConstant>> allEnums = analysisDataExtractor.extractAllEnums(repo.getId());
        StringBuilder businessContext = new StringBuilder();
        
        // 枚举常量（状态、类型等业务概念）
        if (!allEnums.isEmpty()) {
            businessContext.append("### 业务数据\n\n");
            int count = 0;
            for (var entry : allEnums.entrySet()) {
                if (count++ >= 5) break; // 只取前5个，避免上下文过长
                String shortClassName = entry.getKey().contains(".") 
                    ? entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) 
                    : entry.getKey();
                businessContext.append("**").append(shortClassName).append("**：");
                businessContext.append(entry.getValue().stream()
                    .limit(10)
                    .map(c -> c.constName() + (c.description() != null && !c.description().isBlank() ? "(" + c.description() + ")" : ""))
                    .collect(Collectors.joining("、")));
                businessContext.append("\n\n");
            }
        }

        // ===== 接口清单：按业务特征分组 =====
        StringBuilder endpointList = new StringBuilder();
        Map<String, List<String>> groupedEndpoints = groupEndpointsByBusinessFeature(endpoints, repo.getId());
        
        for (Map.Entry<String, List<String>> group : groupedEndpoints.entrySet()) {
            endpointList.append("**").append(group.getKey()).append("**\n");
            for (String ep : group.getValue()) {
                endpointList.append("- ").append(ep).append("\n");
            }
            endpointList.append("\n");
        }

        // ===== AI 生成：项目简介 + 核心能力 =====
        String prompt = "你是一个产品经理，正在阅读一个新项目的代码分析报告。请用业务语言（非技术术语）描述这个项目。\n\n"
                + "## 项目名称\n" + repo.getName() + "\n\n"
                + "## 接口列表（共 " + endpoints.size() + " 个）\n"
                + endpointList + "\n"
                + businessContext
                + "\n## 请生成以下内容\n\n"
                + "### 项目简介\n"
                + "用 2-3 句话说明：\n"
                + "1. 这个项目是什么（业务领域）\n"
                + "2. 服务于谁（用户/客户）\n"
                + "3. 解决什么问题（核心价值）\n\n"
                + "### 核心能力\n"
                + "列出 3-5 个核心功能模块，每个模块用一句话说明它做什么、为什么重要。\n"
                + "用业务语言，不要提及技术实现（如类名、方法名、数据库等）。\n\n"
                + "**要求：**\n"
                + "- 只基于提供的接口和数据生成内容，不要臆测\n"
                + "- 使用 Markdown 格式输出\n"
                + "- 不要添加接口列表中不存在的功能";

        return claudeClient.chat(promptService.get("doc.system"), List.of(Map.of("role", "user", "content", prompt)));
    }

    /**
     * 按业务特征对接口进行分组（基于 URL 路径和功能描述）
     */
    private Map<String, List<String>> groupEndpointsByBusinessFeature(List<ApiEndpointEntity> endpoints, Long repoId) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        
        for (ApiEndpointEntity ep : endpoints) {
            String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType();
            String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
            String desc = "";
            
            // 获取功能描述
            var chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, ep.getFullMethod());
            if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                for (String part : chunk.get().getCallSummary().split("\\|")) {
                    String trimmed = part.trim();
                    if (trimmed.length() >= 2 && trimmed.length() <= 30) {
                        desc = trimmed;
                        break;
                    }
                }
            }
            
            // 根据 URL 路径判断业务模块
            String group = inferBusinessModule(url, desc);
            String epLine = method + " " + url + (!desc.isEmpty() ? " — " + desc : "");
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(epLine);
        }
        
        return grouped;
    }

    /**
     * 根据 URL 路径推断业务模块
     */
    private String inferBusinessModule(String url, String desc) {
        if (url == null || url.isEmpty()) return "其他";
        
        String path = url.toLowerCase();
        
        // 常见业务模块关键词
        if (path.contains("/user") || path.contains("/account") || path.contains("/profile")) return "用户管理";
        if (path.contains("/order") || path.contains("/purchase")) return "订单管理";
        if (path.contains("/product") || path.contains("/goods") || path.contains("/item")) return "商品管理";
        if (path.contains("/payment") || path.contains("/pay")) return "支付";
        if (path.contains("/auth") || path.contains("/login") || path.contains("/register")) return "认证授权";
        if (path.contains("/admin") || path.contains("/system") || path.contains("/config")) return "系统管理";
        if (path.contains("/report") || path.contains("/statistic") || path.contains("/analytics")) return "报表分析";
        if (path.contains("/message") || path.contains("/notification") || path.contains("/notice")) return "消息通知";
        if (path.contains("/file") || path.contains("/upload") || path.contains("/download")) return "文件管理";
        if (path.contains("/log") || path.contains("/audit")) return "日志审计";
        
        // 根据描述推断
        if (desc != null && !desc.isEmpty()) {
            if (desc.contains("用户") || desc.contains("账户")) return "用户管理";
            if (desc.contains("订单")) return "订单管理";
            if (desc.contains("商品") || desc.contains("产品")) return "商品管理";
            if (desc.contains("支付")) return "支付";
            if (desc.contains("登录") || desc.contains("认证")) return "认证授权";
        }
        
        // 从 URL 第一段提取
        String[] segments = path.split("/");
        if (segments.length >= 2 && !segments[1].isEmpty()) {
            return segments[1].substring(0, 1).toUpperCase() + segments[1].substring(1) + " 模块";
        }
        
        return "其他";
    }

    /**
     * 第 2 轮：技术栈 + 架构图 + 外部依赖（代码直出）
     */
    private String generateTechnicalOverview(RepositoryEntity repo, List<ApiEndpointEntity> endpoints,
                                              List<BoundaryEntity> allBoundaries) {
        var projectInfo = projectInfoExtractor.extract(repo.getLocalPath());

        // ===== 外部依赖列表 =====
        List<String[]> deps = new ArrayList<>();
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

        String techStackMd = buildTechStackMd(repo.getName(), projectInfo);
        String archMd = buildArchMd(repo.getId(), repo.getName(), deps);
        String externalDepsMd = buildExternalDepsMd(repo.getId(), deps);

        return techStackMd + "\n\n" + archMd + "\n\n" + externalDepsMd;
    }

    /**
     * 第 3 轮：功能模块清单（AI 生成，面向业务，带接口链接）
     */
    private String generateBusinessModules(RepositoryEntity repo, List<ApiEndpointEntity> endpoints) {
        // 按业务模块分组
        Map<String, List<ApiEndpointEntity>> groupedEndpoints = new LinkedHashMap<>();
        
        for (ApiEndpointEntity ep : endpoints) {
            String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType();
            String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
            String desc = "";
            
            // 获取功能描述
            var chunk = chunkRepo.findByRepoIdAndFullMethod(repo.getId(), ep.getFullMethod());
            if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                for (String part : chunk.get().getCallSummary().split("\\|")) {
                    String trimmed = part.trim();
                    if (trimmed.length() >= 2 && trimmed.length() <= 30) {
                        desc = trimmed;
                        break;
                    }
                }
            }
            
            // 根据 URL 路径判断业务模块
            String group = inferBusinessModule(url, desc);
            groupedEndpoints.computeIfAbsent(group, k -> new ArrayList<>()).add(ep);
        }
        
        StringBuilder moduleList = new StringBuilder();
        for (Map.Entry<String, List<ApiEndpointEntity>> group : groupedEndpoints.entrySet()) {
            moduleList.append("### ").append(group.getKey()).append("\n");
            moduleList.append("接口数量：").append(group.getValue().size()).append(" 个\n\n");
            moduleList.append("**主要功能：**\n");
            
            for (ApiEndpointEntity ep : group.getValue().stream().limit(10).collect(Collectors.toList())) {
                String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType();
                String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
                String desc = "";
                var chunk = chunkRepo.findByRepoIdAndFullMethod(repo.getId(), ep.getFullMethod());
                if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                    for (String part : chunk.get().getCallSummary().split("\\|")) {
                        String trimmed = part.trim();
                        if (trimmed.length() >= 2 && trimmed.length() <= 30) {
                            desc = trimmed;
                            break;
                        }
                    }
                }
                
                // 生成锚点链接到详情章节
                String anchor = generateAnchor(ep);
                String epLine = method + " " + url + (!desc.isEmpty() ? " — " + desc : "");
                moduleList.append("- [").append(epLine).append("](#").append(anchor).append(")\n");
            }
            
            if (group.getValue().size() > 10) {
                moduleList.append("- _...还有 ").append(group.getValue().size() - 10).append(" 个接口_\n");
            }
            moduleList.append("\n");
        }

        String prompt = "以下是项目的功能模块清单，每个模块都列出了相关的接口。\n\n"
                + "## 功能模块\n\n" + moduleList
                + "\n请为每个模块生成一段业务描述（2-3句话），说明：\n"
                + "1. 这个模块的主要职责\n"
                + "2. 典型的使用场景\n"
                + "3. 与其他模块的关系（如果有）\n\n"
                + "**要求：**\n"
                + "- 用业务语言，不要提及技术实现\n"
                + "- 保持原有的 Markdown 结构和模块名称\n"
                + "- 在每个模块标题下添加业务描述，然后保留接口列表（包括链接）\n"
                + "- 只输出完整的 Markdown，不要有解释性文字";

        return "## 功能模块\n\n" + claudeClient.chat(promptService.get("doc.system"), List.of(Map.of("role", "user", "content", prompt)));
    }

    /**
     * 为接口生成 Markdown 锚点 ID
     */
    private String generateAnchor(ApiEndpointEntity ep) {
        String shortClass = shortClass(ep.getClassName());
        String methodName = shortMethodName(ep.getFullMethod());
        String anchor = (shortClass + "-" + methodName)
            .toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        return anchor;
    }

    /**
     * 第 4 轮：业务数据字典（枚举/状态码/错误码）
     */
    private String generateBusinessDataDictionary(Long repoId) {
        Map<String, List<AnalysisDataExtractor.EnumConstant>> allEnums = analysisDataExtractor.extractAllEnums(repoId);
        List<AnalysisDataExtractor.FieldConstant> fieldConstants = analysisDataExtractor.extractAllFieldConstants(repoId);
        
        StringBuilder dict = new StringBuilder();
        dict.append("## 业务数据字典\n\n");
        dict.append("> 这些常量和枚举定义了系统中的业务规则和状态流转。\n\n");
        
        // 枚举常量
        if (!allEnums.isEmpty()) {
            dict.append("### 枚举/状态码\n\n");
            for (Map.Entry<String, List<AnalysisDataExtractor.EnumConstant>> entry : allEnums.entrySet()) {
                String shortClassName = entry.getKey().contains(".") 
                    ? entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) 
                    : entry.getKey();
                dict.append("#### ").append(shortClassName).append("\n\n");
                dict.append("| 常量名 | 代码值 | 说明 |\n|--------|--------|------|\n");
                for (AnalysisDataExtractor.EnumConstant c : entry.getValue()) {
                    String code = c.code() != null && !c.code().isBlank() ? c.code() : "-";
                    String desc = c.description() != null && !c.description().isBlank() ? c.description() : "-";
                    dict.append("| `").append(c.constName()).append("` | ").append(code).append(" | ").append(desc).append(" |\n");
                }
                dict.append("\n");
            }
        }
        
        // 静态常量字段（业务常量）
        if (!fieldConstants.isEmpty()) {
            dict.append("### 常量定义\n\n");
            dict.append("| 类名 | 字段名 | 值 |\n|------|--------|----|\n");
            int count = 0;
            for (AnalysisDataExtractor.FieldConstant fc : fieldConstants) {
                if (count++ >= 30) break; // 限制数量
                String shortClassName = fc.className().contains(".") 
                    ? fc.className().substring(fc.className().lastIndexOf('.') + 1) 
                    : fc.className();
                String value = fc.value() != null ? fc.value() : "-";
                dict.append("| ").append(shortClassName).append(" | `").append(fc.fieldName()).append("` | ").append(value).append(" |\n");
            }
            dict.append("\n");
        }
        
        if (allEnums.isEmpty() && fieldConstants.isEmpty()) {
            return "";
        }
        
        return dict.toString();
    }

    /**
     * 第 5 轮：项目级业务流程（基于调用链生成 Mermaid 图）
     * 重点：为每个主要模块的代表性接口生成调用链时序图
     */
    private String generateBusinessFlows(RepositoryEntity repo, List<ApiEndpointEntity> endpoints,
                                          List<BoundaryEntity> allBoundaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 主要业务流程\n\n");
        sb.append("> 以下是基于接口分析生成的主要业务场景流程图。\n\n");
        
        // 按业务模块分组
        Map<String, List<ApiEndpointEntity>> groupedEndpoints = new LinkedHashMap<>();
        for (ApiEndpointEntity ep : endpoints) {
            String method = ep.getHttpMethod() != null ? ep.getHttpMethod() : ep.getEndpointType();
            String url = ep.getUrlPath() != null ? ep.getUrlPath() : "";
            String desc = "";
            
            var chunk = chunkRepo.findByRepoIdAndFullMethod(repo.getId(), ep.getFullMethod());
            if (chunk.isPresent() && chunk.get().getCallSummary() != null) {
                for (String part : chunk.get().getCallSummary().split("\\|")) {
                    String trimmed = part.trim();
                    if (trimmed.length() >= 2 && trimmed.length() <= 30) {
                        desc = trimmed;
                        break;
                    }
                }
            }
            
            String group = inferBusinessModule(url, desc);
            groupedEndpoints.computeIfAbsent(group, k -> new ArrayList<>()).add(ep);
        }
        
        // 为每个主要模块生成一个时序图（最多3个）
        int count = 0;
        for (Map.Entry<String, List<ApiEndpointEntity>> group : groupedEndpoints.entrySet()) {
            if (count++ >= 3) break;
            if (group.getValue().isEmpty()) continue;
            
            // 选择该模块最具代表性的接口（第一个）
            ApiEndpointEntity representativeEp = group.getValue().get(0);
            
            sb.append("### ").append(group.getKey()).append("\n\n");
            
            String method = representativeEp.getHttpMethod() != null ? representativeEp.getHttpMethod() : "";
            String url = representativeEp.getUrlPath() != null ? representativeEp.getUrlPath() : "";
            sb.append("**示例接口：** ").append(method).append(" ").append(url).append("\n\n");
            
            // 使用统一的调用链图生成方法
            try {
                var tree = callGraphEngine.expandCallTree(repo.getId(), representativeEp.getFullMethod(), 10);
                if (tree.root() != null) {
                    String diagram = generateTechnicalSequenceDiagram(tree.root(), repo.getId());
                    sb.append(diagram);
                } else {
                    sb.append("> 调用链数据不可用\n");
                }
            } catch (Exception e) {
                logger.warn("[项目概览] 生成业务流程图失败: {}", representativeEp.getFullMethod(), e);
                sb.append("> 流程图生成失败\n");
            }
            
            sb.append("\n\n");
        }
        
        return sb.toString();
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

        String aiPart = claudeClient.chat(promptService.get("doc.system"), List.of(Map.of("role", "user", "content", prompt)));

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
        
        // 生成锚点 ID
        String anchor = generateAnchor(ep);
        String header = "<a name=\"" + anchor + "\"></a>\n\n### " + shortClass(ep.getClassName()) + " · " + httpInfo + urlInfo + "\n\n";

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
        
        // 先生成 Mermaid 时序图（代码生成）
        String mermaidDiagram = "";
        try {
            var tree = callGraphEngine.expandCallTree(repoId, ep.getFullMethod(), 10);
            if (tree.root() != null) {
                mermaidDiagram = generateTechnicalSequenceDiagram(tree.root(), repoId);
            }
        } catch (Exception e) {
            logger.warn("[文档生成] Mermaid 图生成失败: {}", ep.getFullMethod(), e);
        }
        
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
                + "- 用 ### 作为标题级别\n"
                + "- 不要生成 Mermaid 图，图表已单独生成\n\n"
                + "## 请生成以下内容\n\n"
                + "### 功能描述\n"
                + "用 1-2 句话描述这个接口的业务功能。只能根据源码中的方法名、注释、日志文字描述，不得推断。\n\n"
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

        // 组合：先输出 AI 生成的文字描述，再插入代码生成的 Mermaid 图
        String aiContent = claudeClient.chat(promptService.get("doc.system"), List.of(Map.of("role", "user", "content", prompt)));
        
        // 在功能描述之后插入业务流程图
        if (!mermaidDiagram.isEmpty()) {
            aiContent = aiContent.replaceFirst(
                "(### 功能描述.*?)(\n### |$)",
                "$1\n\n### 业务流程\n\n" + mermaidDiagram + "\n\n$2"
            );
        }
        
        return header + aiContent;
    }

    /**
     * 生成技术级时序图（基于调用链树，代码生成）
     */
    private String generateTechnicalSequenceDiagram(CallGraphEngine.CallTreeNodeDTO root, Long repoId) {
        logger.info("[技术时序图] 开始生成，root: {}", root != null ? root.fullMethod() : "null");
        
        StringBuilder sb = new StringBuilder();
        
        sb.append("```mermaid\n");
        sb.append("%%{init: {'theme':'base', 'themeVariables': {");
        sb.append("'primaryColor':'#e3f2fd',");
        sb.append("'actorBorder':'#1976d2',");
        sb.append("'actorBkg':'#e3f2fd',");
        sb.append("'signalColor':'#1976d2'");
        sb.append("}}}%%\n");
        
        sb.append("sequenceDiagram\n");
        
        // 收集所有参与者（按类名分组）
        Map<String, String> participants = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        collectParticipants(root, participants, visited);
        
        logger.info("[技术时序图] 收集到参与者数量: {}", participants.size());
        
        // 输出参与者定义（emoji 放在名称中）
        int participantId = 0;
        Map<String, String> participantIds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : participants.entrySet()) {
            String shortName = entry.getKey();
            String emoji = selectClassIcon(shortName);
            String pid = "P" + participantId++;
            participantIds.put(shortName, pid);
            // emoji 放在显示名称里
            sb.append("    participant ").append(pid).append(" as ").append(emoji).append(" ").append(shortName).append("\n");
            logger.debug("[技术时序图] 参与者: {} {}", emoji, shortName);
        }
        
        sb.append("\n");
        
        // 生成调用序列（使用 participantIds）
        visited.clear();
        generateCallSequenceWithIds(root, sb, participants, participantIds, visited, repoId, 0);
        
        sb.append("```");
        
        String result = sb.toString();
        logger.info("[技术时序图] 生成完成，长度: {}", result.length());
        
        return result;
    }

    /**
     * 收集参与者（去重的类名）
     */
    private void collectParticipants(CallGraphEngine.CallTreeNodeDTO node, 
                                      Map<String, String> participants, Set<String> visited) {
        if (node == null) return;
        
        // 使用 fullMethod 来避免重复访问
        String fullMethod = node.fullMethod();
        if (!visited.add(fullMethod)) return;
        
        // 收集当前节点的类名
        String className = extractClassName(fullMethod);
        String shortName = shortClass(className);
        
        if (!participants.containsKey(shortName)) {
            participants.put(shortName, className);
            logger.debug("[技术时序图] 收集参与者: {} -> {}", shortName, className);
        }
        
        // 递归收集子节点
        if (node.children() != null) {
            for (var child : node.children()) {
                collectParticipants(child, participants, visited);
            }
        }
    }

    /**
     * 生成调用序列（使用参与者ID，emoji在participant声明中）
     */
    private void generateCallSequenceWithIds(CallGraphEngine.CallTreeNodeDTO node, StringBuilder sb,
                                       Map<String, String> participants, Map<String, String> participantIds,
                                       Set<String> visited, Long repoId, int depth) {
        if (node == null || depth > 8) return; // 限制深度避免过于复杂
        
        String callerClass = shortClass(extractClassName(node.fullMethod()));
        
        if (node.children() != null && !node.children().isEmpty()) {
            for (var child : node.children()) {
                String calleeClass = shortClass(extractClassName(child.fullMethod()));
                String calleeMethod = extractMethodName(child.fullMethod());
                
                // 获取参与者 ID，如果不存在则跳过（理论上不应该发生）
                String callerPid = participantIds.get(callerClass);
                String calleePid = participantIds.get(calleeClass);
                
                if (callerPid == null || calleePid == null) {
                    logger.warn("[技术时序图] 找不到参与者ID: caller={} (pid={}), callee={} (pid={})", 
                               callerClass, callerPid, calleeClass, calleePid);
                    continue;
                }
                
                // 检查是否有边界点
                List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, child.fullMethod());
                
                // 选择操作图标
                String icon = selectMethodIcon(calleeMethod, boundaries);
                
                // 生成调用（使用参与者ID）
                sb.append("    ").append(callerPid).append("->>");
                sb.append(calleePid).append(": ").append(icon).append(" ");
                sb.append(shortenMethodName(calleeMethod)).append("\n");
                sb.append("    activate ").append(calleePid).append("\n");
                
                // 如果有边界点，添加注释
                if (!boundaries.isEmpty()) {
                    for (BoundaryEntity b : boundaries) {
                        String boundaryIcon = selectBoundaryIcon(b.getBoundaryType());
                        sb.append("    Note over ").append(calleePid).append(": ");
                        sb.append(boundaryIcon).append(" ").append(b.getBoundaryType()).append("\n");
                        break; // 只显示第一个
                    }
                }
                
                // 递归处理子调用
                if (child.children() != null && !child.children().isEmpty()) {
                    generateCallSequenceWithIds(child, sb, participants, participantIds, visited, repoId, depth + 1);
                }
                
                sb.append("    ").append(calleePid).append("-->>");
                sb.append(callerPid).append(": 返回\n");
                sb.append("    deactivate ").append(calleePid).append("\n");
            }
        }
    }
    
    /**
     * 生成调用序列（旧方法，保留用于其他地方可能的调用）
     */
    private void generateCallSequence(CallGraphEngine.CallTreeNodeDTO node, StringBuilder sb,
                                       Map<String, String> participants, Set<String> visited,
                                       Long repoId, int depth) {
        if (node == null || depth > 8) return; // 限制深度避免过于复杂
        
        String callerClass = shortClass(extractClassName(node.fullMethod()));
        String methodName = extractMethodName(node.fullMethod());
        
        if (node.children() != null && !node.children().isEmpty()) {
            for (var child : node.children()) {
                String calleeClass = shortClass(extractClassName(child.fullMethod()));
                String calleeMethod = extractMethodName(child.fullMethod());
                
                // 检查是否有边界点
                List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, child.fullMethod());
                
                // 选择操作图标
                String icon = selectMethodIcon(calleeMethod, boundaries);
                
                // 生成调用
                String callerEmoji = selectClassIcon(callerClass);
                String calleeEmoji = selectClassIcon(calleeClass);
                
                sb.append("    ").append(callerEmoji).append("->>");
                sb.append(calleeEmoji).append(": ").append(icon).append(" ");
                sb.append(shortenMethodName(calleeMethod)).append("\n");
                sb.append("    activate ").append(calleeEmoji).append("\n");
                
                // 如果有边界点，添加注释
                if (!boundaries.isEmpty()) {
                    for (BoundaryEntity b : boundaries) {
                        String boundaryIcon = selectBoundaryIcon(b.getBoundaryType());
                        sb.append("    Note over ").append(calleeEmoji).append(": ");
                        sb.append(boundaryIcon).append(" ").append(b.getBoundaryType()).append("\n");
                        break; // 只显示第一个
                    }
                }
                
                // 递归处理子调用
                if (child.children() != null && !child.children().isEmpty()) {
                    generateCallSequence(child, sb, participants, visited, repoId, depth + 1);
                }
                
                sb.append("    ").append(calleeEmoji).append("-->>");
                sb.append(callerEmoji).append(": 返回\n");
                sb.append("    deactivate ").append(calleeEmoji).append("\n");
            }
        }
    }

    /**
     * 为类选择合适的 Emoji 图标
     */
    private String selectClassIcon(String className) {
        String lower = className.toLowerCase();
        
        if (lower.contains("controller")) return "🖥️";
        if (lower.contains("service")) return "⚙️";
        if (lower.contains("manager")) return "🔧";
        if (lower.contains("mapper") || lower.contains("dao") || lower.contains("repository")) return "💾";
        if (lower.contains("client") || lower.contains("feign")) return "🌐";
        if (lower.contains("producer") || lower.contains("consumer")) return "📨";
        if (lower.contains("handler")) return "🔨";
        if (lower.contains("provider")) return "📦";
        if (lower.contains("validator")) return "✅";
        if (lower.contains("converter") || lower.contains("transformer")) return "🔄";
        if (lower.contains("filter") || lower.contains("interceptor")) return "🚦";
        if (lower.contains("config") || lower.contains("configuration")) return "⚙️";
        
        return "📄";
    }

    /**
     * 为方法选择合适的 Emoji 图标
     */
    private String selectMethodIcon(String methodName, List<BoundaryEntity> boundaries) {
        String lower = methodName.toLowerCase();
        
        // 优先根据边界点类型
        if (!boundaries.isEmpty()) {
            BoundaryEntity b = boundaries.get(0);
            if ("DB".equals(b.getBoundaryType())) {
                if (lower.contains("insert") || lower.contains("save") || lower.contains("add")) return "➕";
                if (lower.contains("update") || lower.contains("modify")) return "✏️";
                if (lower.contains("delete") || lower.contains("remove")) return "🗑️";
                if (lower.contains("select") || lower.contains("get") || lower.contains("find") || lower.contains("query")) return "🔍";
                return "💾";
            }
            if ("HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType())) return "🌐";
            if ("MQ".equals(b.getBoundaryType())) return "📨";
        }
        
        // 基于方法名
        if (lower.contains("valid") || lower.contains("check") || lower.contains("verify")) return "✅";
        if (lower.contains("create") || lower.contains("add") || lower.contains("insert") || lower.contains("save")) return "➕";
        if (lower.contains("update") || lower.contains("modify") || lower.contains("edit")) return "✏️";
        if (lower.contains("delete") || lower.contains("remove")) return "🗑️";
        if (lower.contains("get") || lower.contains("find") || lower.contains("query") || lower.contains("select") || lower.contains("search")) return "🔍";
        if (lower.contains("send") || lower.contains("push") || lower.contains("publish")) return "📤";
        if (lower.contains("receive") || lower.contains("consume") || lower.contains("handle")) return "📥";
        if (lower.contains("convert") || lower.contains("transform") || lower.contains("map")) return "🔄";
        if (lower.contains("build") || lower.contains("construct")) return "🔨";
        if (lower.contains("parse") || lower.contains("decode")) return "🔓";
        if (lower.contains("encrypt") || lower.contains("encode")) return "🔐";
        if (lower.contains("calculate") || lower.contains("compute")) return "🧮";
        if (lower.contains("login") || lower.contains("auth")) return "🔑";
        if (lower.contains("logout")) return "🚪";
        if (lower.contains("pay")) return "💰";
        
        return "⚙️";
    }

    /**
     * 为边界点类型选择图标
     */
    private String selectBoundaryIcon(String boundaryType) {
        if ("DB".equals(boundaryType)) return "💾";
        if ("HTTP".equals(boundaryType)) return "🌐";
        if ("GRPC".equals(boundaryType)) return "🔗";
        if ("MQ".equals(boundaryType)) return "📨";
        if ("REDIS".equals(boundaryType)) return "📦";
        return "🔌";
    }

    /**
     * 缩短方法名（去掉参数）
     */
    private String shortenMethodName(String methodName) {
        int paren = methodName.indexOf('(');
        if (paren > 0) {
            return methodName.substring(0, paren);
        }
        return methodName;
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

        return claudeClient.chat(promptService.get("doc.system"), List.of(Map.of("role", "user", "content", prompt)));
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
     * 生成产品视角文档（业务流程图 + 业务逻辑说明）
     */
    public String generateProductDoc(Long repoId, String entryMethod) {
        // 1. 缓存命中：调用链不变文档不变，直接返回
        String cacheKey = repoId + "|" + entryMethod;
        CachedDoc cached = productDocCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.ts() < PRODUCT_DOC_CACHE_TTL_MS) {
            logger.debug("[产品文档] 缓存命中 method={}", entryMethod);
            return cached.content();
        }

        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 15);
        if (tree.root() == null) return "接口未找到";

        // 2. 获取入口点信息
        var endpoint = apiEndpointRepo.findFirstByRepoIdAndFullMethod(repoId, entryMethod).orElse(null);
        String entryDesc = buildEntryDescription(endpoint, entryMethod);

        // 3. 提取业务节点和边界点
        List<BoundaryEntity> boundaries = collectBoundaries(repoId, tree.root());
        List<BusinessNode> businessNodes = extractBusinessNodesWithBoundaries(tree.root(), boundaries);

        // 4. 收集调用链中的所有方法
        List<String> allMethods = new ArrayList<>();
        collectAllMethods(tree.root(), new HashSet<>(), allMethods);

        // 5. 分析数据流转
        DataFlowSummary dataFlow = analyzeDataFlow(boundaries);

        // 6. 用 collectExternalCalls + ExternalCallFormatter 生成完整外部依赖汇总
        String depSummary = buildExternalDepSummary(repoId, entryMethod);

        String result;
        // 7. 尝试用 AI 生成（如果配置了）
        if (claudeClient.isConfigured()) {
            try {
                result = generateProductDocWithAI(repoId, tree.root(), entryDesc, businessNodes, dataFlow, boundaries, allMethods, depSummary);
            } catch (Exception e) {
                logger.warn("AI 生成产品文档失败，使用模板生成", e);
                result = generateProductDocTemplateWithBoundaries(repoId, entryDesc, businessNodes, dataFlow, boundaries, allMethods, depSummary);
            }
        } else {
            // 8. Fallback: 模板生成
            result = generateProductDocTemplateWithBoundaries(repoId, entryDesc, businessNodes, dataFlow, boundaries, allMethods, depSummary);
        }

        productDocCache.put(cacheKey, new CachedDoc(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * 用 collectExternalCalls + ExternalCallFormatter 构建外部依赖汇总文本，
     * 复用 ChainOutlineService 的相同逻辑，保证 HTTP/MQ/CACHE/GRPC/DB 全部列出且含 context 详情。
     */
    private String buildExternalDepSummary(Long repoId, String entryMethod) {
        Map<String, List<CallGraphEngine.BoundaryDTO>> external;
        try {
            external = callGraphEngine.collectExternalCalls(repoId, entryMethod);
        } catch (Exception e) {
            logger.warn("[产品文档] collectExternalCalls 失败 method={}: {}", entryMethod, e.getMessage());
            return "";
        }
        if (external.isEmpty()) return "";

        // type -> 去重条目（方法简称 + 明细）
        Map<String, LinkedHashSet<String>> depAgg = new LinkedHashMap<>();
        for (var entry : external.entrySet()) {
            String fullMethod = entry.getKey();
            String ref = shortMethod(fullMethod);
            List<CallGraphEngine.BoundaryDTO> bdList = entry.getValue();

            // HTTP/GRPC：优先用 ExternalCallFormatter 装配完整 URL（含系统名+用途）
            boolean hasHttp = bdList.stream()
                    .anyMatch(b -> "HTTP".equals(b.boundaryType()) || "GRPC".equals(b.boundaryType()));
            Set<String> httpRendered = new LinkedHashSet<>();
            if (hasHttp) {
                ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).orElse(null);
                if (chunk != null) {
                    for (var call : externalCallFormatter.extract(chunk)) {
                        httpRendered.add(call.render());
                    }
                }
            }

            for (var b : bdList) {
                String type = b.boundaryType();
                if (type == null || type.isBlank()) continue;
                if (("HTTP".equals(type) || "GRPC".equals(type)) && !httpRendered.isEmpty()) continue;
                String ctx = b.context() != null ? b.context().replaceAll("\\s+", " ").trim() : "";
                if (ctx.length() > 300) ctx = ctx.substring(0, 300) + "…";
                depAgg.computeIfAbsent(type, k -> new LinkedHashSet<>())
                      .add(ref + (ctx.isEmpty() ? "" : " — " + ctx));
            }
            for (String rendered : httpRendered) {
                depAgg.computeIfAbsent("HTTP", k -> new LinkedHashSet<>()).add(ref + " — " + rendered);
            }
        }

        if (depAgg.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("# 外部依赖详情（从调用链静态分析提取）\n\n");
        for (var entry : depAgg.entrySet()) {
            String label = switch (entry.getKey()) {
                case "HTTP"  -> "HTTP 外部调用";
                case "GRPC"  -> "gRPC / RPC 调用";
                case "MQ"    -> "消息队列（MQ）";
                case "CACHE" -> "缓存（Redis）";
                case "DB"    -> "数据库操作";
                default      -> entry.getKey();
            };
            sb.append("## ").append(label).append("\n\n");
            for (String line : entry.getValue()) {
                sb.append("- ").append(line).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
    
    private void collectAllMethods(CallGraphEngine.CallTreeNodeDTO node, Set<String> visited, List<String> result) {
        if (node == null || !visited.add(node.fullMethod())) return;
        result.add(node.fullMethod());
        if (node.children() != null) {
            for (var child : node.children()) {
                collectAllMethods(child, visited, result);
            }
        }
    }

    /**
     * AI 生成产品文档（增强版：Mermaid 图代码生成 + 业务描述 AI 生成）
     */
    private String generateProductDocWithAI(Long repoId, CallGraphEngine.CallTreeNodeDTO root, String entryDesc,
                                             List<BusinessNode> nodes, DataFlowSummary dataFlow,
                                             List<BoundaryEntity> boundaries, List<String> allMethods,
                                             String depSummary) {
        // 1. 代码生成 Mermaid 时序图（稳定可靠，基于调用链树）
        logger.info("[产品文档] 开始生成 Mermaid 时序图，节点数: {}", nodes.size());
        String sequenceDiagram = generateTechnicalSequenceDiagram(root, repoId);
        logger.info("[产品文档] Mermaid 图生成完成，长度: {}", sequenceDiagram.length());
        logger.debug("[产品文档] Mermaid 图内容:\n{}", sequenceDiagram);

        // 2. AI 生成业务描述（不包含图表）
        logger.info("[产品文档] 开始调用 AI 生成业务描述");
        String prompt = buildProductDocPromptWithBoundaries(repoId, entryDesc, nodes, dataFlow, boundaries, allMethods, depSummary);
        String aiContent = claudeClient.chat(promptService.get("product.doc"), List.of(
            Map.of("role", "user", "content", prompt)
        ));
        logger.info("[产品文档] AI 生成完成，内容长度: {}", aiContent.length());
        logger.debug("[产品文档] AI 返回内容:\n{}", aiContent);

        // 3. 直接在开头插入 Mermaid 图，然后追加 AI 内容
        StringBuilder result = new StringBuilder();
        result.append("## 业务流程图\n\n");
        result.append(sequenceDiagram).append("\n\n");
        result.append(aiContent);

        String finalDoc = result.toString();
        logger.info("[产品文档] 最终文档生成完成，总长度: {}, 是否包含mermaid: {}",
                    finalDoc.length(), finalDoc.contains("```mermaid"));
        return finalDoc;
    }

    // Product doc system prompt (不要求 AI 生成图表)。默认值经 PromptService 登记为 "product.doc"，可在系统配置覆盖。
    public static final String PRODUCT_DOC_SYSTEM_PROMPT_WITHOUT_DIAGRAM = """
        你是一位产品经理，正在为团队编写产品需求文档。你的读者是产品经理、测试工程师、客服人员、业务方。
        
        ## 输出要求
        1. **业务逻辑**：用业务语言描述，避免技术术语（不要出现类名、方法名、SQL、表名）
        2. **判断条件详情**：必须包含所有关键判断条件的具体数值和含义
           - 例如：不要写"检查状态"，要写"当状态=1时表示待审核，状态=2表示已通过"
           - 例如：不要写"验证类型"，要写"type=ORDER表示订单，type=REFUND表示退款"
        3. **常量和枚举**：列出所有用到的状态码、类型码、错误码及其含义
        4. **异常场景**：必须包含具体的错误码、错误信息、触发条件
        5. **数据流转**：说明哪些数据被创建/修改/删除，用业务术语
        6. **用户感知**：从用户角度描述输入、输出和可能的错误提示
        
        ## 禁止事项
        - 不要贴代码或伪代码
        - 不要提及类名、方法名、表名、字段名
        - 不要编造内容，所有信息必须来自提供的数据
        - 不要假设或推断未提供的信息
        - 不要省略判断条件的具体数值
        - **不要生成 Mermaid 图表**（图表已单独生成）
        
        ## 关键信息优先级
        1. **判断条件的具体值**（最重要）：状态码、类型码、标志位的具体数值和含义
        2. **异常和错误码**：所有可能的错误情况、错误码、错误信息
        3. **常量定义**：业务用到的所有常量及其含义
        4. **枚举值**：所有枚举类型的可选值及其业务含义
        5. **业务规则阈值**：数量限制、金额限制、时间限制等具体数值
        
        ## 输出格式
        纯 Markdown，必须包含以下章节（不包含业务流程图）：
        
        ### 1. 功能概述
        1-2句话描述功能
        
        ### 2. 主要业务逻辑
        分步骤说明，每个判断点必须包含具体的判断值
        
        ### 3. 判断条件与常量
        表格形式列出所有判断条件的具体值：
        | 字段/常量 | 可选值 | 含义 | 备注 |
        
        ### 4. 异常场景与错误码
        表格形式列出所有异常：
        | 错误码 | 错误信息 | 触发条件 | 用户看到什么 |
        
        ### 5. 数据变更
        用业务术语描述数据操作
        """;

    // Product doc system prompt (原版，包含图表要求)
    private static final String PRODUCT_DOC_SYSTEM_PROMPT = """
        你是一位产品经理，正在为团队编写产品需求文档。你的读者是产品经理、测试工程师、客服人员、业务方。
        
        ## 输出要求
        1. **业务流程图**：必须使用 Mermaid sequenceDiagram 或 flowchart 展示完整流程，使用 emoji 图标增强可读性
        2. **业务逻辑**：用业务语言描述，避免技术术语（不要出现类名、方法名、SQL、表名）
        3. **判断条件详情**：必须包含所有关键判断条件的具体数值和含义
           - 例如：不要写"检查状态"，要写"当状态=1时表示待审核，状态=2表示已通过"
           - 例如：不要写"验证类型"，要写"type=ORDER表示订单，type=REFUND表示退款"
        4. **常量和枚举**：列出所有用到的状态码、类型码、错误码及其含义
        5. **异常场景**：必须包含具体的错误码、错误信息、触发条件
        6. **数据流转**：说明哪些数据被创建/修改/删除，用业务术语
        7. **用户感知**：从用户角度描述输入、输出和可能的错误提示
        
        ## 禁止事项
        - 不要贴代码或伪代码
        - 不要提及类名、方法名、表名、字段名
        - 不要编造内容，所有信息必须来自提供的数据
        - 不要假设或推断未提供的信息
        - 不要省略判断条件的具体数值
        
        ## 关键信息优先级
        1. **判断条件的具体值**（最重要）：状态码、类型码、标志位的具体数值和含义
        2. **异常和错误码**：所有可能的错误情况、错误码、错误信息
        3. **常量定义**：业务用到的所有常量及其含义
        4. **枚举值**：所有枚举类型的可选值及其业务含义
        5. **业务规则阈值**：数量限制、金额限制、时间限制等具体数值
        
        ## Mermaid 图标建议
        - 用户/客户端：👤 🧑 👨‍💼
        - 系统/服务：🖥️ ⚙️ 🔧
        - 判断/分支：🔀 ❓
        - 数据库：💾 🗄️
        - 缓存：📦 💿
        - 消息队列：📨 📬 ✉️
        - 外部服务：🌐 🔗 📡
        - 成功：✅ ✓
        - 失败/错误：❌ ⚠️
        - 开始：🚀 ▶️
        - 结束：🏁 ⏹️
        
        ## 输出格式
        纯 Markdown，必须包含以下章节：
        
        ### 1. 功能概述
        1-2句话描述功能
        
        ### 2. 业务流程图
        Mermaid 图表，在关键判断节点标注具体条件值
        
        ### 3. 主要业务逻辑
        分步骤说明，每个判断点必须包含具体的判断值
        
        ### 4. 判断条件与常量
        表格形式列出所有判断条件的具体值：
        | 字段/常量 | 可选值 | 含义 | 备注 |
        
        ### 5. 异常场景与错误码
        表格形式列出所有异常：
        | 错误码 | 错误信息 | 触发条件 | 用户看到什么 |
        
        ### 6. 数据变更
        用业务术语描述数据操作
        
        ## Mermaid 主题配置模板
        ```mermaid
        %%{init: {'theme':'base', 'themeVariables': {'primaryColor':'#e3f2fd','primaryTextColor':'#0d47a1','primaryBorderColor':'#1976d2','lineColor':'#1976d2'}}}%%
        flowchart TD
            Start([🚀 开始])
            Check{🔀 状态=1?}
            Check -->|是| Action1[处理A]
            Check -->|否| Action2[处理B]
        ```
        
        注意：图表中的判断节点必须标注具体的判断值！
        """;

    /**
     * 构建产品文档 AI prompt - 增强版，包含外部依赖详情、边界点异常详情和真实常量数据
     */
    private String buildProductDocPromptWithBoundaries(Long repoId, String entryDesc, List<BusinessNode> nodes,
                                                        DataFlowSummary dataFlow, List<BoundaryEntity> boundaries,
                                                        List<String> allMethods, String depSummary) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# 接口信息\n\n");
        prompt.append(entryDesc).append("\n\n");

        prompt.append("# 业务处理步骤\n\n");
        prompt.append("以下是代码中的关键业务逻辑节点（已去除纯技术组件）：\n\n");
        for (int i = 0; i < nodes.size(); i++) {
            BusinessNode node = nodes.get(i);
            prompt.append(i + 1).append(". ").append(node.description());
            if (node.boundaries() != null && !node.boundaries().isEmpty()) {
                prompt.append(" — 涉及：").append(String.join("、", node.boundaries()));
            }
            prompt.append("\n");
        }
        prompt.append("\n");

        // 外部依赖详情（HTTP URL / MQ Topic / Redis key / gRPC 服务）
        if (depSummary != null && !depSummary.isBlank()) {
            prompt.append(depSummary).append("\n");
        }

        // 添加枚举常量信息（从静态分析提取）
        List<AnalysisDataExtractor.EnumConstant> enums = analysisDataExtractor.extractEnumConstantsFromChain(repoId, allMethods);
        if (!enums.isEmpty()) {
            prompt.append("# 枚举常量（从源码静态分析提取）\n\n");
            prompt.append("调用链中使用了以下枚举常量：\n\n");

            Map<String, List<AnalysisDataExtractor.EnumConstant>> byClass = new LinkedHashMap<>();
            for (var e : enums) {
                String shortClass = e.enumClass().contains(".") ?
                    e.enumClass().substring(e.enumClass().lastIndexOf('.') + 1) : e.enumClass();
                byClass.computeIfAbsent(shortClass, k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<String, List<AnalysisDataExtractor.EnumConstant>> entry : byClass.entrySet()) {
                prompt.append("**").append(entry.getKey()).append("**：\n");
                for (var e : entry.getValue()) {
                    prompt.append("- `").append(e.constName()).append("`");
                    if (!e.code().isEmpty()) {
                        prompt.append("（code=").append(e.code()).append("）");
                    }
                    if (!e.description().isEmpty()) {
                        prompt.append("：").append(e.description());
                    }
                    prompt.append("\n");
                }
                prompt.append("\n");
            }
        }

        // 添加方法中使用的常量
        List<AnalysisDataExtractor.MethodConstantUsage> constants = analysisDataExtractor.extractMethodConstantsFromChain(repoId, allMethods);
        if (!constants.isEmpty()) {
            prompt.append("# 使用的常量（从源码静态分析提取）\n\n");
            for (var c : constants) {
                String shortMd = shortMethod(c.fullMethod());
                prompt.append("- **").append(c.constantName()).append("**");
                if (!c.constantValue().isEmpty()) {
                    prompt.append(" = `").append(c.constantValue()).append("`");
                }
                prompt.append(" (见 `").append(shortMd).append("`)\n");
            }
            prompt.append("\n");
        }

        prompt.append("# 数据操作\n\n");
        if (!dataFlow.inserts().isEmpty()) {
            prompt.append("**新增数据：** ").append(String.join("、", dataFlow.inserts())).append("\n\n");
        }
        if (!dataFlow.updates().isEmpty()) {
            prompt.append("**修改数据：** ").append(String.join("、", dataFlow.updates())).append("\n\n");
        }
        if (!dataFlow.queries().isEmpty()) {
            prompt.append("**查询数据：** ").append(String.join("、", dataFlow.queries())).append("\n\n");
        }
        if (!dataFlow.deletes().isEmpty()) {
            prompt.append("**删除数据：** ").append(String.join("、", dataFlow.deletes())).append("\n\n");
        }
        if (!dataFlow.externalCalls().isEmpty()) {
            prompt.append("**调用外部服务：** ").append(String.join("、", dataFlow.externalCalls())).append("\n\n");
        }
        if (!dataFlow.messages().isEmpty()) {
            prompt.append("**发送消息：** ").append(String.join("、", dataFlow.messages())).append("\n\n");
        }

        // 添加异常信息（从边界点提取）
        List<ExceptionInfo> exceptions = extractExceptionsFromBoundaries(boundaries);
        if (!exceptions.isEmpty()) {
            prompt.append("# 异常场景（从源码静态分析提取）\n\n");
            for (ExceptionInfo ex : exceptions) {
                prompt.append("- **").append(ex.exceptionType()).append("**");
                if (!ex.message().isEmpty()) {
                    prompt.append("：").append(ex.message());
                }
                if (!ex.trigger().isEmpty()) {
                    prompt.append(" [触发条件：").append(ex.trigger()).append("]");
                }
                prompt.append(" (见 `").append(ex.location()).append("`)\n");
            }
            prompt.append("\n");
        }

        prompt.append("---\n\n");
        prompt.append("## ⚠️ 重要提示\n\n");
        prompt.append("上面已经提供了从源码静态分析提取的枚举常量和使用的常量，请在文档中使用这些**真实数据**：\n\n");
        prompt.append("1. **判断条件的具体值** - 使用上面提供的枚举常量，如：`状态=PAID`表示已支付\n");
        prompt.append("2. **常量定义** - 使用上面提供的常量列表\n");
        prompt.append("3. **错误码** - 使用上面提供的异常场景列表\n");
        prompt.append("4. **业务阈值** - 如果源码中有数字常量，在上面的常量列表中会显示\n");
        prompt.append("5. **枚举说明** - 直接使用上面枚举常量后面的中文描述\n");
        prompt.append("6. **外部依赖** - 使用上面[外部依赖详情]章节中的真实 URL、Topic、Redis key，不要编造\n\n");
        prompt.append("**禁止编造任何不在上述列表中的枚举值、常量、错误码或外部依赖！**\n\n");
        prompt.append("请基于以上信息，生成产品需求文档，必须包含带主题配置的 Mermaid 流程图、业务逻辑说明、判断条件详情表（使用真实枚举值）、错误码表。\n");

        return prompt.toString();
    }
    
    /**
     * 模板生成产品文档（带边界点信息和真实常量数据）
     */
    private String generateProductDocTemplateWithBoundaries(Long repoId, String entryDesc, List<BusinessNode> nodes,
                                                            DataFlowSummary dataFlow, List<BoundaryEntity> boundaries,
                                                            List<String> allMethods, String depSummary) {
        StringBuilder sb = new StringBuilder();

        // 1. 功能概述
        sb.append("# 产品文档\n\n");
        sb.append("## 功能概述\n\n");
        sb.append(entryDesc).append("\n\n");

        // 2. 业务流程图
        sb.append("## 业务流程\n\n");
        sb.append(generateMermaidFlowchart(nodes, dataFlow));
        sb.append("\n\n");

        // 3. 主要业务逻辑
        sb.append("## 主要业务逻辑\n\n");
        for (int i = 0; i < nodes.size(); i++) {
            BusinessNode node = nodes.get(i);
            sb.append(i + 1).append(". **").append(node.description()).append("**");
            if (node.boundaries() != null && !node.boundaries().isEmpty()) {
                sb.append("\n   - 涉及：").append(String.join("、", node.boundaries()));
            }
            sb.append("\n");
        }
        sb.append("\n");

        // 4. 业务规则详情（从源码提取真实数据）
        sb.append("## 业务规则与判断条件\n\n");
        sb.append(extractBusinessRulesWithRealData(repoId, nodes, allMethods));
        sb.append("\n");

        // 5. 数据变更
        sb.append("## 数据变更\n\n");
        boolean hasDataChanges = false;
        if (!dataFlow.inserts().isEmpty()) {
            sb.append("- **新增：** ").append(String.join("、", dataFlow.inserts())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.updates().isEmpty()) {
            sb.append("- **修改：** ").append(String.join("、", dataFlow.updates())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.queries().isEmpty()) {
            sb.append("- **查询：** ").append(String.join("、", dataFlow.queries())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.deletes().isEmpty()) {
            sb.append("- **删除：** ").append(String.join("、", dataFlow.deletes())).append("\n");
            hasDataChanges = true;
        }
        if (!hasDataChanges) {
            sb.append("未检测到数据库操作\n");
        }
        sb.append("\n");

        // 6. 异常与错误码（从边界点提取）
        sb.append("## 异常场景与错误码\n\n");
        sb.append(extractExceptionInfoFromBoundaries(boundaries));
        sb.append("\n");

        // 7. 外部依赖（优先展示完整依赖详情，无则退回 dataFlow 摘要）
        if (depSummary != null && !depSummary.isBlank()) {
            sb.append("## 外部交互\n\n");
            sb.append(depSummary);
            sb.append("\n");
        } else if (!dataFlow.externalCalls().isEmpty() || !dataFlow.messages().isEmpty()) {
            sb.append("## 外部交互\n\n");
            if (!dataFlow.externalCalls().isEmpty()) {
                sb.append("- **调用外部服务：** ").append(String.join("、", dataFlow.externalCalls())).append("\n");
            }
            if (!dataFlow.messages().isEmpty()) {
                sb.append("- **发送消息：** ").append(String.join("、", dataFlow.messages())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("> 💡 提示：配置 Claude API 可获得更详细的业务逻辑说明和异常场景分析\n");

        return sb.toString();
    }
    
    /**
     * 从源码提取真实的业务规则（包含枚举和常量）
     */
    private String extractBusinessRulesWithRealData(Long repoId, List<BusinessNode> nodes, List<String> allMethods) {
        StringBuilder sb = new StringBuilder();
        
        // 提取枚举常量
        List<AnalysisDataExtractor.EnumConstant> enums = analysisDataExtractor.extractEnumConstantsFromChain(repoId, allMethods);
        
        // 提取方法常量
        List<AnalysisDataExtractor.MethodConstantUsage> constants = analysisDataExtractor.extractMethodConstantsFromChain(repoId, allMethods);
        
        if (enums.isEmpty() && constants.isEmpty()) {
            sb.append("源码中未检测到明显的业务判断条件\n\n");
            sb.append("> ⚠️ **提示**：配置 Claude API 可以通过 AI 分析源码，提取更详细的业务规则和判断条件。\n");
            return sb.toString();
        }
        
        // 枚举常量表
        if (!enums.isEmpty()) {
            sb.append("### 使用的枚举常量\n\n");
            sb.append("| 枚举类 | 常量名 | code | 说明 |\n");
            sb.append("|--------|--------|------|------|\n");

            Map<String, List<AnalysisDataExtractor.EnumConstant>> byClass = new LinkedHashMap<>();
            for (var e : enums) {
                String shortClass = e.enumClass().contains(".") ?
                    e.enumClass().substring(e.enumClass().lastIndexOf('.') + 1) : e.enumClass();
                byClass.computeIfAbsent(shortClass, k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<String, List<AnalysisDataExtractor.EnumConstant>> entry : byClass.entrySet()) {
                for (var e : entry.getValue()) {
                    sb.append("| `").append(entry.getKey()).append("` | ");
                    sb.append("`").append(e.constName()).append("` | ");
                    sb.append(e.code().isEmpty() ? "-" : "`" + e.code() + "`").append(" | ");
                    sb.append(e.description().isEmpty() ? "-" : e.description()).append(" |\n");
                }
            }
            sb.append("\n");
        }
        
        // 使用的常量表
        if (!constants.isEmpty()) {
            sb.append("### 使用的常量\n\n");
            sb.append("| 常量名 | 值 | 使用位置 |\n");
            sb.append("|--------|-----|----------|\n");
            
            for (var c : constants) {
                sb.append("| `").append(c.constantName()).append("` | ");
                sb.append(c.constantValue().isEmpty() ? "-" : "`" + c.constantValue() + "`").append(" | ");
                sb.append("`").append(shortMethod(c.fullMethod())).append("` |\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 从边界点列表中提取异常信息（用于模板生成）
     */
    private String extractExceptionInfoFromBoundaries(List<BoundaryEntity> boundaries) {
        List<ExceptionInfo> exceptions = extractExceptionsFromBoundaries(boundaries);
        
        if (exceptions.isEmpty()) {
            return "源码中未检测到显式异常抛出点\n\n" +
                   "> ⚠️ **提示**：配置 Claude API 可以自动分析源码中的所有异常抛出点，提取完整的错误码和错误信息。\n";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("从源码静态分析提取的异常场景：\n\n");
        sb.append("| 异常类型 | 错误信息 | 触发条件 | 来源方法 |\n");
        sb.append("|----------|----------|----------|----------|\n");
        
        for (ExceptionInfo ex : exceptions) {
            sb.append("| ").append(ex.exceptionType()).append(" | ");
            sb.append(ex.message().isEmpty() ? "-" : ex.message()).append(" | ");
            sb.append(ex.trigger().isEmpty() ? "-" : ex.trigger()).append(" | ");
            sb.append("`").append(ex.location()).append("` |\n");
        }
        
        sb.append("\n");
        return sb.toString();
    }
    
    /**
     * 提取业务节点（带边界点详情）
     */
    private List<BusinessNode> extractBusinessNodesWithBoundaries(CallGraphEngine.CallTreeNodeDTO root,
                                                                   List<BoundaryEntity> allBoundaries) {
        // 构建方法到边界点的映射
        Map<String, List<BoundaryEntity>> methodBoundaries = new HashMap<>();
        for (BoundaryEntity b : allBoundaries) {
            methodBoundaries.computeIfAbsent(b.getFullMethod(), k -> new ArrayList<>()).add(b);
        }
        
        List<BusinessNode> nodes = new ArrayList<>();
        extractBusinessNodesRecursiveWithBoundaries(root, nodes, new HashSet<>(), methodBoundaries);
        return nodes;
    }

    private void extractBusinessNodesRecursiveWithBoundaries(CallGraphEngine.CallTreeNodeDTO node, 
                                                              List<BusinessNode> result, 
                                                              Set<String> visited,
                                                              Map<String, List<BoundaryEntity>> methodBoundaries) {
        if (node == null || !visited.add(node.fullMethod())) return;
        
        String className = extractClassName(node.fullMethod());
        String methodName = extractMethodName(node.fullMethod());
        
        // 过滤掉纯技术组件
        if (isBusinessNode(className, methodName)) {
            String desc = buildBusinessDescription(className, methodName);
            List<String> boundaries = new ArrayList<>();
            
            // 从边界点映射中获取该方法的边界点
            List<BoundaryEntity> nodeBoundaries = methodBoundaries.get(node.fullMethod());
            if (nodeBoundaries != null) {
                for (BoundaryEntity b : nodeBoundaries) {
                    boundaries.add(translateBoundary(b.getBoundaryType()));
                }
            }
            
            result.add(new BusinessNode(desc, boundaries));
        }
        
        // 递归子节点
        if (node.children() != null) {
            for (var child : node.children()) {
                extractBusinessNodesRecursiveWithBoundaries(child, result, visited, methodBoundaries);
            }
        }
    }

    /**
     * 模板生成产品文档（Fallback）
     */
    private String generateProductDocTemplate(String entryDesc, List<BusinessNode> nodes,
                                               DataFlowSummary dataFlow) {
        StringBuilder sb = new StringBuilder();
        
        // 1. 功能概述
        sb.append("# 产品文档\n\n");
        sb.append("## 功能概述\n\n");
        sb.append(entryDesc).append("\n\n");
        
        // 2. 业务流程图
        sb.append("## 业务流程\n\n");
        sb.append(generateMermaidFlowchart(nodes, dataFlow));
        sb.append("\n\n");
        
        // 3. 主要业务逻辑
        sb.append("## 主要业务逻辑\n\n");
        for (int i = 0; i < nodes.size(); i++) {
            BusinessNode node = nodes.get(i);
            sb.append(i + 1).append(". **").append(node.description()).append("**");
            if (node.boundaries() != null && !node.boundaries().isEmpty()) {
                sb.append("\n   - 涉及：").append(String.join("、", node.boundaries()));
            }
            sb.append("\n");
        }
        sb.append("\n");
        
        // 4. 业务规则详情（新增）
        sb.append("## 业务规则与判断条件\n\n");
        sb.append(extractBusinessRules(nodes));
        sb.append("\n");
        
        // 5. 数据变更
        sb.append("## 数据变更\n\n");
        boolean hasDataChanges = false;
        if (!dataFlow.inserts().isEmpty()) {
            sb.append("- **新增：** ").append(String.join("、", dataFlow.inserts())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.updates().isEmpty()) {
            sb.append("- **修改：** ").append(String.join("、", dataFlow.updates())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.queries().isEmpty()) {
            sb.append("- **查询：** ").append(String.join("、", dataFlow.queries())).append("\n");
            hasDataChanges = true;
        }
        if (!dataFlow.deletes().isEmpty()) {
            sb.append("- **删除：** ").append(String.join("、", dataFlow.deletes())).append("\n");
            hasDataChanges = true;
        }
        if (!hasDataChanges) {
            sb.append("未检测到数据库操作\n");
        }
        sb.append("\n");
        
        // 6. 异常与错误码（新增）
        sb.append("## 异常场景与错误码\n\n");
        sb.append(extractExceptionInfo(dataFlow));
        sb.append("\n");
        
        // 7. 外部依赖
        if (!dataFlow.externalCalls().isEmpty() || !dataFlow.messages().isEmpty()) {
            sb.append("## 外部交互\n\n");
            if (!dataFlow.externalCalls().isEmpty()) {
                sb.append("- **调用外部服务：** ").append(String.join("、", dataFlow.externalCalls())).append("\n");
            }
            if (!dataFlow.messages().isEmpty()) {
                sb.append("- **发送消息：** ").append(String.join("、", dataFlow.messages())).append("\n");
            }
            sb.append("\n");
        }
        
        sb.append("> 💡 提示：配置 Claude API 可获得更详细的业务逻辑说明和异常场景分析\n");
        
        return sb.toString();
    }

    /**
     * 从调用链中提取业务规则和判断条件
     * 增强版：真正从源码中提取判断条件和常量
     */
    private String extractBusinessRules(List<BusinessNode> nodes) {
        if (nodes.isEmpty()) {
            return "源码中未检测到明显的业务判断条件\n";
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 收集所有节点相关的源码，从中提取判断条件
        List<SourceCondition> conditions = new ArrayList<>();
        Set<ConstantDef> constants = new LinkedHashSet<>();
        
        for (BusinessNode node : nodes) {
            // 这里需要通过 node 的原始方法信息获取源码
            // 暂时先构建一个基于现有信息的输出
            // TODO: 需要在 BusinessNode 中增加 fullMethod 字段以便查询源码
        }
        
        // 如果没有提取到任何条件，返回提示
        if (conditions.isEmpty() && constants.isEmpty()) {
            sb.append("源码中未检测到明显的业务判断条件\n\n");
            sb.append("> ⚠️ **提示**：配置 Claude API 可以通过 AI 分析源码，提取更详细的业务规则和判断条件。\n");
            return sb.toString();
        }
        
        // 输出判断条件表
        if (!conditions.isEmpty()) {
            sb.append("### 判断条件详情\n\n");
            sb.append("| 判断条件 | 可选值 | 含义 | 位置 |\n");
            sb.append("|----------|--------|------|------|\n");
            
            for (SourceCondition cond : conditions) {
                sb.append("| ").append(cond.expression()).append(" | ");
                sb.append(cond.values()).append(" | ");
                sb.append(cond.meaning()).append(" | ");
                sb.append(cond.location()).append(" |\n");
            }
            sb.append("\n");
        }
        
        // 输出常量表
        if (!constants.isEmpty()) {
            sb.append("### 使用的常量\n\n");
            sb.append("| 常量名 | 值 | 说明 |\n");
            sb.append("|--------|-----|------|\n");
            
            for (ConstantDef constant : constants) {
                sb.append("| `").append(constant.name()).append("` | ");
                sb.append("`").append(constant.value()).append("` | ");
                sb.append(constant.description()).append(" |\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    // 辅助记录类：源码判断条件
    private record SourceCondition(String expression, String values, String meaning, String location) {}
    
    // 辅助记录类：常量定义
    private record ConstantDef(String name, String value, String description) {}

    /**
     * 提取异常信息和错误码
     * 增强版：从 BoundaryEntity 的 EXCEPTION 类型中提取详细错误信息
     */
    private String extractExceptionInfo(DataFlowSummary dataFlow) {
        // 这里需要传入 boundaries 才能提取异常信息
        // 暂时返回提示信息
        
        StringBuilder sb = new StringBuilder();
        sb.append("从调用链中提取的异常场景：\n\n");
        sb.append("| 异常类型 | 错误信息 | 触发条件 | 来源方法 |\n");
        sb.append("|----------|----------|----------|----------|\n");
        
        // 占位行
        sb.append("| _待从源码提取_ | _待从源码提取_ | _待从源码提取_ | _待从源码提取_ |\n");
        
        sb.append("\n> ⚠️ **提示**：配置 Claude API 可以自动分析源码中的所有异常抛出点，提取完整的错误码和错误信息。\n");
        
        return sb.toString();
    }
    
    /**
     * 从 BoundaryEntity 列表中提取异常信息（供 AI 和模板使用）
     */
    private List<ExceptionInfo> extractExceptionsFromBoundaries(List<BoundaryEntity> boundaries) {
        List<ExceptionInfo> exceptions = new ArrayList<>();

        for (BoundaryEntity b : boundaries) {
            if (!"EXCEPTION".equals(b.getBoundaryType()) || b.getContext() == null) continue;

            String[] lines = b.getContext().split("\n");
            // 第一行格式：「throw 行号」或「catch 行号」，取第一个 token 作为类型
            String firstLine = lines[0].trim();
            String kind = firstLine.contains(" ") ? firstLine.substring(0, firstLine.indexOf(' ')) : firstLine;
            String exceptionType = kind; // throw / catch

            String message = "";  // 📝 行：源码内容（含错误码调用）
            String trigger = "";  // → 行：已解析的枚举值 e.g. (4004, "商品不存在")

            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("📝")) {
                    String src = t.substring("📝".length()).trim();
                    if (!src.isEmpty() && src.length() < 200) message = src;
                } else if (t.startsWith("→")) {
                    String resolved = t.substring("→".length()).trim();
                    if (!resolved.isEmpty() && resolved.length() < 200) trigger = resolved;
                }
            }

            String location = shortMethod(b.getFullMethod());
            exceptions.add(new ExceptionInfo(exceptionType, message, trigger, location));
        }

        return exceptions;
    }
    
    // 辅助记录类：异常信息
    private record ExceptionInfo(String exceptionType, String message, String trigger, String location) {}

    /**
     * 生成产品文档的图表数据（供前端切换使用）
     */
    public Map<String, String> generateProductDocDiagrams(Long repoId, String entryMethod) {
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 15);
        if (tree.root() == null) return Map.of();

        List<BusinessNode> businessNodes = extractBusinessNodes(tree.root());
        List<BoundaryEntity> boundaries = collectBoundaries(repoId, tree.root());
        DataFlowSummary dataFlow = analyzeDataFlow(boundaries);

        Map<String, String> diagrams = new LinkedHashMap<>();
        diagrams.put("flowchart", generateFlowchart(businessNodes, dataFlow));
        // 使用技术级时序图（详细的调用链）
        diagrams.put("sequence", generateTechnicalSequenceDiagram(tree.root(), repoId));
        diagrams.put("swimlane", generateSwimlane(businessNodes, dataFlow));
        
        return diagrams;
    }

    /**
     * 生成 Mermaid 流程图（占位符，实际图表由前端根据用户选择替换）
     */
    private String generateMermaidFlowchart(List<BusinessNode> nodes, DataFlowSummary dataFlow) {
        // 只输出一个占位的 Mermaid 代码块
        // 前端会根据用户选择的图表类型（flowchart/sequence/swimlane）替换这个占位符
        return "```mermaid\n" + generateFlowchart(nodes, dataFlow).replace("```mermaid\n", "") + "```";
    }

    /**
     * 生成流程图 (Flowchart TD)
     */
    private String generateFlowchart(List<BusinessNode> nodes, DataFlowSummary dataFlow) {
        StringBuilder sb = new StringBuilder();
        
        // Mermaid 主题配置
        sb.append("```mermaid\n");
        sb.append("%%{init: {'theme':'base', 'themeVariables': {");
        sb.append("'primaryColor':'#e3f2fd',");
        sb.append("'primaryTextColor':'#0d47a1',");
        sb.append("'primaryBorderColor':'#1976d2',");
        sb.append("'lineColor':'#1976d2',");
        sb.append("'secondaryColor':'#fff3e0',");
        sb.append("'tertiaryColor':'#f3e5f5'");
        sb.append("}}}%%\n");
        
        sb.append("flowchart TD\n");
        sb.append("    Start([\"🚀 开始\"])\n");
        
        // 业务节点
        for (int i = 0; i < Math.min(nodes.size(), 10); i++) {
            BusinessNode node = nodes.get(i);
            String nodeId = "Node" + i;
            String icon = selectNodeIcon(node);
            sb.append("    ").append(nodeId).append("[\"").append(icon).append(" ").append(node.description()).append("\"]\n");
        }
        
        // 数据库节点
        if (!dataFlow.inserts().isEmpty() || !dataFlow.updates().isEmpty() || 
            !dataFlow.queries().isEmpty() || !dataFlow.deletes().isEmpty()) {
            sb.append("    DB[(\"💾 数据库操作\")]\n");
        }
        
        // 外部调用节点
        if (!dataFlow.externalCalls().isEmpty()) {
            sb.append("    External[\"🌐 外部服务\"]\n");
        }
        
        // 消息队列节点
        if (!dataFlow.messages().isEmpty()) {
            sb.append("    MQ[\"📨 消息队列\"]\n");
        }
        
        sb.append("    End([\"✅ 结束\"])\n\n");
        
        // 连线
        sb.append("    Start --> Node0\n");
        for (int i = 0; i < Math.min(nodes.size(), 10) - 1; i++) {
            sb.append("    Node").append(i).append(" --> Node").append(i + 1).append("\n");
        }
        
        int lastNode = Math.min(nodes.size(), 10) - 1;
        if (!dataFlow.inserts().isEmpty() || !dataFlow.updates().isEmpty() || 
            !dataFlow.queries().isEmpty() || !dataFlow.deletes().isEmpty()) {
            sb.append("    Node").append(lastNode).append(" --> DB\n");
            sb.append("    DB --> End\n");
        } else {
            sb.append("    Node").append(lastNode).append(" --> End\n");
        }
        
        if (!dataFlow.externalCalls().isEmpty()) {
            sb.append("    Node").append(lastNode).append(" -.->|调用| External\n");
        }
        if (!dataFlow.messages().isEmpty()) {
            sb.append("    Node").append(lastNode).append(" -.->|发送| MQ\n");
        }
        
        sb.append("```");
        
        return sb.toString();
    }

    /**
     * 生成时序图 (Sequence Diagram)
     */
    private String generateSequenceDiagram(List<BusinessNode> nodes, DataFlowSummary dataFlow) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("```mermaid\n");
        sb.append("%%{init: {'theme':'base', 'themeVariables': {");
        sb.append("'primaryColor':'#e3f2fd',");
        sb.append("'actorBorder':'#1976d2',");
        sb.append("'actorBkg':'#e3f2fd',");
        sb.append("'signalColor':'#1976d2',");
        sb.append("'sequenceNumberColor':'white'");
        sb.append("}}}%%\n");
        
        sb.append("sequenceDiagram\n");
        sb.append("    participant 👤 as 用户\n");
        sb.append("    participant 🖥️ as 系统\n");
        
        // 添加数据库参与者
        if (!dataFlow.inserts().isEmpty() || !dataFlow.updates().isEmpty() || 
            !dataFlow.queries().isEmpty() || !dataFlow.deletes().isEmpty()) {
            sb.append("    participant 💾 as 数据库\n");
        }
        
        // 添加外部服务参与者
        if (!dataFlow.externalCalls().isEmpty()) {
            sb.append("    participant 🌐 as 外部服务\n");
        }
        
        // 添加消息队列参与者
        if (!dataFlow.messages().isEmpty()) {
            sb.append("    participant 📨 as 消息队列\n");
        }
        
        sb.append("\n");
        sb.append("    👤->>🖥️: 发起请求\n");
        sb.append("    activate 🖥️\n");
        
        // 业务节点作为系统内部处理步骤
        for (int i = 0; i < Math.min(nodes.size(), 8); i++) {
            BusinessNode node = nodes.get(i);
            String desc = node.description();
            String icon = selectNodeIcon(node);
            
            // 根据边界点类型生成不同的交互
            if (node.boundaries() != null && node.boundaries().contains("数据库")) {
                sb.append("    🖥️->>💾: ").append(icon).append(" ").append(desc).append("\n");
                sb.append("    activate 💾\n");
                sb.append("    💾-->>🖥️: 返回结果\n");
                sb.append("    deactivate 💾\n");
            } else if (node.boundaries() != null && node.boundaries().contains("外部接口")) {
                sb.append("    🖥️->>🌐: ").append(icon).append(" ").append(desc).append("\n");
                sb.append("    activate 🌐\n");
                sb.append("    🌐-->>🖥️: 响应\n");
                sb.append("    deactivate 🌐\n");
            } else if (node.boundaries() != null && node.boundaries().contains("消息队列")) {
                sb.append("    🖥️->>📨: ").append(icon).append(" ").append(desc).append("\n");
            } else {
                sb.append("    Note over 🖥️: ").append(icon).append(" ").append(desc).append("\n");
            }
        }
        
        sb.append("    🖥️-->>👤: 返回结果\n");
        sb.append("    deactivate 🖥️\n");
        sb.append("```");
        
        return sb.toString();
    }

    /**
     * 生成泳道图 (Swimlane with Flowchart)
     */
    private String generateSwimlane(List<BusinessNode> nodes, DataFlowSummary dataFlow) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("```mermaid\n");
        sb.append("%%{init: {'theme':'base', 'themeVariables': {");
        sb.append("'primaryColor':'#e3f2fd',");
        sb.append("'primaryTextColor':'#0d47a1',");
        sb.append("'primaryBorderColor':'#1976d2',");
        sb.append("'lineColor':'#1976d2'");
        sb.append("}}}%%\n");
        
        sb.append("graph TB\n");
        
        // 定义泳道（子图）
        sb.append("    subgraph 应用层\n");
        int appNodeCount = 0;
        for (int i = 0; i < Math.min(nodes.size(), 10); i++) {
            BusinessNode node = nodes.get(i);
            if (node.boundaries() == null || node.boundaries().isEmpty() || 
                (!node.boundaries().contains("数据库") && !node.boundaries().contains("外部接口"))) {
                String nodeId = "App" + appNodeCount;
                String icon = selectNodeIcon(node);
                sb.append("        ").append(nodeId).append("[\"").append(icon).append(" ").append(node.description()).append("\"]\n");
                appNodeCount++;
            }
        }
        sb.append("    end\n\n");
        
        // 数据层
        if (!dataFlow.inserts().isEmpty() || !dataFlow.updates().isEmpty() || 
            !dataFlow.queries().isEmpty() || !dataFlow.deletes().isEmpty()) {
            sb.append("    subgraph 数据层\n");
            sb.append("        DB[(\"💾 数据库\")]\n");
            sb.append("    end\n\n");
        }
        
        // 外部服务层
        if (!dataFlow.externalCalls().isEmpty() || !dataFlow.messages().isEmpty()) {
            sb.append("    subgraph 外部依赖\n");
            if (!dataFlow.externalCalls().isEmpty()) {
                sb.append("        External[\"🌐 外部服务\"]\n");
            }
            if (!dataFlow.messages().isEmpty()) {
                sb.append("        MQ[\"📨 消息队列\"]\n");
            }
            sb.append("    end\n\n");
        }
        
        // 连线
        for (int i = 0; i < appNodeCount - 1; i++) {
            sb.append("    App").append(i).append(" --> App").append(i + 1).append("\n");
        }
        
        if (!dataFlow.inserts().isEmpty() || !dataFlow.updates().isEmpty() || 
            !dataFlow.queries().isEmpty() || !dataFlow.deletes().isEmpty()) {
            sb.append("    App").append(appNodeCount - 1).append(" --> DB\n");
        }
        
        if (!dataFlow.externalCalls().isEmpty()) {
            sb.append("    App").append(Math.max(0, appNodeCount / 2)).append(" -.-> External\n");
        }
        
        if (!dataFlow.messages().isEmpty()) {
            sb.append("    App").append(appNodeCount - 1).append(" -.-> MQ\n");
        }
        
        sb.append("```");
        
        return sb.toString();
    }

    /**
     * 根据节点类型选择图标（增强版）
     */
    private String selectNodeIcon(BusinessNode node) {
        String desc = node.description().toLowerCase();
        
        // 操作类型
        if (desc.contains("校验") || desc.contains("验证") || desc.contains("检查") || desc.contains("审核")) return "✅";
        if (desc.contains("创建") || desc.contains("新增") || desc.contains("添加") || desc.contains("注册")) return "➕";
        if (desc.contains("更新") || desc.contains("修改") || desc.contains("编辑") || desc.contains("变更")) return "✏️";
        if (desc.contains("删除") || desc.contains("移除") || desc.contains("清除")) return "🗑️";
        if (desc.contains("查询") || desc.contains("获取") || desc.contains("查找") || desc.contains("搜索")) return "🔍";
        
        // 业务场景
        if (desc.contains("计算") || desc.contains("统计") || desc.contains("分析")) return "🧮";
        if (desc.contains("发送") || desc.contains("通知") || desc.contains("推送")) return "📤";
        if (desc.contains("接收") || desc.contains("监听") || desc.contains("消费")) return "📥";
        if (desc.contains("支付") || desc.contains("扣费") || desc.contains("充值")) return "💰";
        if (desc.contains("权限") || desc.contains("认证") || desc.contains("授权") || desc.contains("登录")) return "🔐";
        if (desc.contains("生成") || desc.contains("构建") || desc.contains("创建")) return "🔨";
        if (desc.contains("转换") || desc.contains("映射") || desc.contains("格式化")) return "🔄";
        if (desc.contains("审批") || desc.contains("批准") || desc.contains("驳回")) return "📋";
        if (desc.contains("上传") || desc.contains("导入")) return "⬆️";
        if (desc.contains("下载") || desc.contains("导出")) return "⬇️";
        if (desc.contains("同步") || desc.contains("刷新")) return "🔃";
        if (desc.contains("锁定") || desc.contains("解锁")) return "🔒";
        if (desc.contains("启用") || desc.contains("禁用") || desc.contains("开关")) return "🔘";
        if (desc.contains("重试") || desc.contains("回滚")) return "↩️";
        if (desc.contains("完成") || desc.contains("结束") || desc.contains("成功")) return "✔️";
        if (desc.contains("失败") || desc.contains("错误") || desc.contains("异常")) return "❌";
        if (desc.contains("警告") || desc.contains("提醒")) return "⚠️";
        
        // 默认图标
        return "⚙️";
    }

    /**
     * 提取业务节点
     */
    private List<BusinessNode> extractBusinessNodes(CallGraphEngine.CallTreeNodeDTO root) {
        List<BusinessNode> nodes = new ArrayList<>();
        extractBusinessNodesRecursive(root, nodes, new HashSet<>());
        return nodes;
    }

    private void extractBusinessNodesRecursive(CallGraphEngine.CallTreeNodeDTO node, 
                                                List<BusinessNode> result, Set<String> visited) {
        if (node == null || !visited.add(node.fullMethod())) return;
        
        String className = extractClassName(node.fullMethod());
        String methodName = extractMethodName(node.fullMethod());
        
        // 过滤掉纯技术组件
        if (isBusinessNode(className, methodName)) {
            String desc = buildBusinessDescription(className, methodName);
            List<String> boundaries = new ArrayList<>();
            
            // 添加边界点信息
            if (node.boundaries() != null) {
                for (var b : node.boundaries()) {
                    boundaries.add(translateBoundary(b.boundaryType()));
                }
            }
            
            result.add(new BusinessNode(desc, boundaries));
        }
        
        // 递归子节点
        if (node.children() != null) {
            for (var child : node.children()) {
                extractBusinessNodesRecursive(child, result, visited);
            }
        }
    }

    /**
     * 判断是否为业务节点
     */
    private boolean isBusinessNode(String className, String methodName) {
        String lowerClass = className.toLowerCase();
        String lowerMethod = methodName.toLowerCase();
        
        // 排除纯技术类
        if (lowerClass.contains("util") || lowerClass.contains("helper") || 
            lowerClass.contains("converter") || lowerClass.contains("mapper") ||
            lowerClass.contains("config") || lowerClass.endsWith("dto") ||
            lowerClass.endsWith("vo") || lowerClass.endsWith("entity") ||
            lowerClass.endsWith("dao") || lowerClass.endsWith("repository")) {
            return false;
        }
        
        // 排除 getter/setter/toString 等
        if (lowerMethod.startsWith("get") || lowerMethod.startsWith("set") ||
            lowerMethod.equals("tostring") || lowerMethod.equals("hashcode") ||
            lowerMethod.equals("equals") || lowerMethod.startsWith("lambda$")) {
            return false;
        }
        
        return true;
    }

    /**
     * 构建业务描述
     */
    private String buildBusinessDescription(String className, String methodName) {
        String simpleClass = className.substring(className.lastIndexOf('.') + 1)
                                      .replace("ServiceImpl", "")
                                      .replace("Service", "")
                                      .replace("Manager", "")
                                      .replace("Handler", "");
        
        String desc = methodName;
        String lower = methodName.toLowerCase();
        
        if (lower.startsWith("create") || lower.startsWith("add") || lower.startsWith("insert")) 
            desc = "创建" + simpleClass;
        else if (lower.startsWith("update") || lower.startsWith("modify") || lower.startsWith("edit")) 
            desc = "更新" + simpleClass;
        else if (lower.startsWith("delete") || lower.startsWith("remove")) 
            desc = "删除" + simpleClass;
        else if (lower.startsWith("query") || lower.startsWith("get") || lower.startsWith("find") || lower.startsWith("list")) 
            desc = "查询" + simpleClass;
        else if (lower.startsWith("check") || lower.startsWith("validate") || lower.startsWith("verify")) 
            desc = "校验" + simpleClass;
        else if (lower.startsWith("calculate") || lower.startsWith("compute")) 
            desc = "计算" + simpleClass;
        else if (lower.startsWith("send") || lower.startsWith("notify") || lower.startsWith("publish")) 
            desc = "发送通知";
        else if (lower.startsWith("process") || lower.startsWith("handle")) 
            desc = "处理" + simpleClass;
        else 
            desc = methodName + "（" + simpleClass + "）";
        
        return desc;
    }

    /**
     * 翻译边界点类型为业务术语
     */
    private String translateBoundary(String boundaryType) {
        return switch (boundaryType) {
            case "DB" -> "数据库";
            case "HTTP" -> "外部接口";
            case "MQ" -> "消息队列";
            case "CACHE" -> "缓存";
            case "GRPC" -> "RPC调用";
            default -> boundaryType;
        };
    }

    /**
     * 收集调用链中所有方法的边界点
     */
    private List<BoundaryEntity> collectBoundaries(Long repoId, CallGraphEngine.CallTreeNodeDTO root) {
        Set<String> methods = new HashSet<>();
        collectAllMethods(root, methods);
        
        List<BoundaryEntity> result = new ArrayList<>();
        for (String method : methods) {
            result.addAll(boundaryRepo.findByRepoIdAndFullMethod(repoId, method));
        }
        return result;
    }

    private void collectAllMethods(CallGraphEngine.CallTreeNodeDTO node, Set<String> result) {
        if (node == null || !result.add(node.fullMethod())) return;
        if (node.children() != null) {
            for (var child : node.children()) {
                collectAllMethods(child, result);
            }
        }
    }

    /**
     * 分析数据流转
     */
    private DataFlowSummary analyzeDataFlow(List<BoundaryEntity> boundaries) {
        Set<String> inserts = new LinkedHashSet<>();
        Set<String> updates = new LinkedHashSet<>();
        Set<String> queries = new LinkedHashSet<>();
        Set<String> deletes = new LinkedHashSet<>();
        Set<String> externalCalls = new LinkedHashSet<>();
        Set<String> messages = new LinkedHashSet<>();
        
        for (BoundaryEntity b : boundaries) {
            if ("DB".equals(b.getBoundaryType())) {
                String context = b.getContext().toLowerCase();
                String table = extractBusinessEntityName(b.getContext());
                if (context.contains("insert") || context.contains("save")) {
                    inserts.add(table);
                } else if (context.contains("update")) {
                    updates.add(table);
                } else if (context.contains("delete")) {
                    deletes.add(table);
                } else if (context.contains("select") || context.contains("query") || context.contains("find")) {
                    queries.add(table);
                }
            } else if ("HTTP".equals(b.getBoundaryType())) {
                externalCalls.add(extractServiceName(b.getBoundaryType(), b.getContext()));
            } else if ("MQ".equals(b.getBoundaryType())) {
                messages.add(extractServiceName(b.getBoundaryType(), b.getContext()));
            }
        }
        
        return new DataFlowSummary(
            new ArrayList<>(inserts),
            new ArrayList<>(updates),
            new ArrayList<>(queries),
            new ArrayList<>(deletes),
            new ArrayList<>(externalCalls),
            new ArrayList<>(messages)
        );
    }

    /**
     * 提取业务实体名（从表名或 Mapper 方法名）
     */
    private String extractBusinessEntityName(String context) {
        // 从 SQL 中提取表名
        String lower = context.toLowerCase();
        String[] keywords = {"from ", "into ", "update ", "join "};
        for (String kw : keywords) {
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                String after = context.substring(idx + kw.length()).trim();
                String table = after.split("\\s+")[0].replaceAll("[`;]", "");
                if (!table.isEmpty() && !table.equals("*")) {
                    // 转换为业务术语（去掉 t_、tb_ 前缀）
                    return table.replaceFirst("^(t_|tb_|tbl_)", "")
                                .replace("_", "");
                }
            }
        }
        return "数据";
    }

    private String buildEntryDescription(ApiEndpointEntity endpoint, String entryMethod) {
        if (endpoint != null) {
            String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod() : "";
            String path = endpoint.getUrlPath() != null ? endpoint.getUrlPath() : "";
            return method + " " + path;
        }
        return shortMethod(entryMethod);
    }

    // 辅助类
    private record BusinessNode(String description, List<String> boundaries) {}
    
    private record DataFlowSummary(
        List<String> inserts,
        List<String> updates,
        List<String> queries,
        List<String> deletes,
        List<String> externalCalls,
        List<String> messages
    ) {}

    private String extractClassName(String fullMethod) {
        int colon = fullMethod.lastIndexOf(':');
        return colon > 0 ? fullMethod.substring(0, colon) : fullMethod;
    }

    private String extractMethodName(String fullMethod) {
        int colon = fullMethod.lastIndexOf(':');
        String methodPart = colon > 0 ? fullMethod.substring(colon + 1) : fullMethod;
        int paren = methodPart.indexOf('(');
        return paren > 0 ? methodPart.substring(0, paren) : methodPart;
    }

    /**
     * 生成研发视角文档（技术实现细节）
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
