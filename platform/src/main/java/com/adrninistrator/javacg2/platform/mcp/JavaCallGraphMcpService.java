package com.adrninistrator.javacg2.platform.mcp;

import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.ApiEndpointRepo;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.CallGraphRepo;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.EmbeddingService;
import com.adrninistrator.javacg2.platform.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP Server 工具集：将 Java 调用链图谱能力暴露给 AI 编程助手（Claude Code / Cursor / Kiro）。
 * 通过 @Tool 注解自动注册为 MCP 工具，Spring AI MCP starter 负责协议封装。
 */
@Service
public class JavaCallGraphMcpService {

    private static final Logger logger = LoggerFactory.getLogger(JavaCallGraphMcpService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_DEPTH = 5;  // 从20改为5，避免性能问题

    private final CallGraphEngine callGraphEngine;
    private final BoundaryRepo boundaryRepo;
    private final ApiEndpointRepo apiEndpointRepo;
    private final RepositoryRepo repositoryRepo;
    private final CallGraphRepo callGraphRepo;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    public JavaCallGraphMcpService(CallGraphEngine callGraphEngine,
                                    BoundaryRepo boundaryRepo,
                                    ApiEndpointRepo apiEndpointRepo,
                                    RepositoryRepo repositoryRepo,
                                    CallGraphRepo callGraphRepo,
                                    EmbeddingService embeddingService,
                                    VectorStoreService vectorStoreService) {
        this.callGraphEngine = callGraphEngine;
        this.boundaryRepo = boundaryRepo;
        this.apiEndpointRepo = apiEndpointRepo;
        this.repositoryRepo = repositoryRepo;
        this.callGraphRepo = callGraphRepo;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    // ── 工具 1：列出所有已分析的仓库 ─────────────────────────────────────────

    @Tool(name = "listRepositories",
          description = "列出所有已完成分析的 Java 仓库。返回仓库 ID、名称和分析状态。" +
                        "在使用其他工具前，先调用此工具获取有效的 repoId。")
    public String listRepositories() {
        try {
            List<RepositoryEntity> repos = repositoryRepo.findAll().stream()
                    .filter(r -> "READY".equals(r.getStatus()))
                    .collect(Collectors.toList());

            ObjectNode result = mapper.createObjectNode();
            ArrayNode repoArray = result.putArray("repositories");
            for (RepositoryEntity r : repos) {
                ObjectNode node = mapper.createObjectNode();
                node.put("id", r.getId());
                node.put("name", r.getName());
                node.put("status", r.getStatus());
                node.put("branch", r.getBranch());
                if (r.getLastSyncTime() != null) node.put("lastSyncTime", r.getLastSyncTime().toString());
                repoArray.add(node);
            }
            result.put("total", repos.size());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] listRepositories 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具 2：获取方法调用链 ────────────────────────────────────────────────

    @Tool(name = "getCallGraph",
          description = "获取指定方法的调用链树，展示该方法会调用哪些下游方法（向下展开）。" +
                        "适合用于：了解某个接口的完整实现路径、修改前评估影响范围。" +
                        "fullMethod 格式：类全限定名:方法名(参数类型列表)，例如 com.example.OrderController:createOrder(com.example.dto.OrderReq)")
    public String getCallGraph(
            @ToolParam(description = "仓库 ID，从 listRepositories 获取") long repoId,
            @ToolParam(description = "入口方法完整签名，格式：类全限定名:方法名(参数类型列表)") String entryMethod,
            @ToolParam(description = "展开深度，建议 3-10，最大 20") int maxDepth) {
        try {
            if (!repositoryExists(repoId)) {
                return "{\"error\": \"仓库不存在或未分析完成: " + repoId + "\"}";
            }
            int depth = Math.min(maxDepth, MAX_DEPTH);
            var tree = callGraphEngine.expandCallTree(repoId, entryMethod, depth);
            if (tree == null || tree.root() == null) {
                return "{\"error\": \"未找到方法: " + entryMethod + "\"}";
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("entryMethod", entryMethod);
            result.put("totalNodes", tree.totalNodes());
            result.put("maxDepth", depth);
            result.put("hasCycle", tree.hasCycle());
            result.set("callTree", buildCallTreeNode(tree.root()));

            logger.info("[MCP] getCallGraph repoId={} method={} nodes={}", repoId, entryMethod, tree.totalNodes());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getCallGraph 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具 3：影响分析（反向调用链）────────────────────────────────────────

    @Tool(name = "getImpactAnalysis",
          description = "影响分析：查找哪些方法会调用到指定方法（单仓库内，向上追溯直接调用者）。" +
                        "【建议】若你的目的是『修改底层方法、评估会破坏哪些上层逻辑』，请优先使用 getImpactWithSource，" +
                        "它会一路追到对外接口并附带每个调用点的源码片段；本工具仅返回直接调用者列表（一层）、不含源码。" +
                        "若需跨多个项目追踪，请使用 getCrossRepoImpact。" +
                        "适合用于：快速查看某方法的直接调用者及是否为 API 入口。")
    public String getImpactAnalysis(
            @ToolParam(description = "仓库 ID") long repoId,
            @ToolParam(description = "被修改的方法完整签名") String fullMethod) {
        try {
            if (!repositoryExists(repoId)) {
                return "{\"error\": \"仓库不存在或未分析完成: " + repoId + "\"}";
            }
            List<CallGraphEngine.CallerDTO> callers = callGraphEngine.getCallers(repoId, fullMethod, 1);
            // 同时返回该仓库中的 API 入口点（便于 AI 判断影响范围）
            List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);

            ObjectNode result = mapper.createObjectNode();
            result.put("targetMethod", fullMethod);
            result.put("shortRef", extractShortRef(fullMethod));

            ArrayNode callersNode = result.putArray("directCallers");
            for (var caller : callers) {
                ObjectNode c = mapper.createObjectNode();
                c.put("method", caller.fullMethod());
                c.put("shortRef", extractShortRef(caller.fullMethod()));
                c.put("callType", caller.callType() != null ? caller.callType() : "CALL");
                if (caller.lineNumber() != null) c.put("lineNumber", caller.lineNumber());
                // 标注是否是 API 入口点
                boolean isEndpoint = endpoints.stream().anyMatch(e -> e.getFullMethod().equals(caller.fullMethod()));
                c.put("isApiEndpoint", isEndpoint);
                callersNode.add(c);
            }

            result.put("callerCount", callers.size());
            long endpointCount = callers.stream().filter(c ->
                    endpoints.stream().anyMatch(e -> e.getFullMethod().equals(c.fullMethod()))).count();
            result.put("affectedEndpoints", endpointCount);

            // 跨库提示：检测其他仓库是否也调用了该方法，提示 AI 升级到跨库工具
            long crossRepoCallers = callGraphRepo.findByCalleeMethod(fullMethod).stream()
                    .filter(c -> c.getRepoId() != null && c.getRepoId() != repoId)
                    .map(c -> c.getRepoId() + "|" + c.getCallerMethod())
                    .distinct().count();
            if (crossRepoCallers > 0) {
                result.put("crossRepoCallerCount", crossRepoCallers);
                result.put("hint", "检测到其他仓库也调用了此方法，建议调用 getCrossRepoImpact 了解完整的跨项目影响范围。");
            }

            logger.info("[MCP] getImpactAnalysis repoId={} method={} callers={}", repoId, fullMethod, callers.size());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getImpactAnalysis 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具：影响分析 + 调用点源码（评估底层改动首选）─────────────────────────
    @Tool(name = "getImpactWithSource",
          description = "【改底层方法首选】影响分析 + 调用点源码一站式返回：向上追溯所有调用方直到对外接口（Controller/MQ/定时任务），" +
                        "并为每个调用方附带【它调用本方法那一行附近的源码片段】以及调用方/被调用方的返回类型。" +
                        "适合用于：修改某个底层/公共方法前，一次性看清『谁调用了它、在哪一行、怎么用它的返回值/异常』，" +
                        "直接判断改动会不会破坏上层调用方的契约假设，无需再逐个调用 getMethodSource 回查。" +
                        "fullMethod 格式：类全限定名:方法名(参数类型列表)。maxDepth 建议 5-10，snippetRadius 建议 3-6。")
    public String getImpactWithSource(
            @ToolParam(description = "被修改的方法完整签名，格式：类全限定名:方法名(参数类型列表)") String fullMethod,
            @ToolParam(description = "向上追溯的最大深度，建议 5-10，最大 15") int maxDepth,
            @ToolParam(description = "调用点上下文行数（上下各取几行），建议 3-6") int snippetRadius) {
        try {
            CallGraphEngine.ImpactWithSourceDTO impact =
                    callGraphEngine.getImpactWithSource(fullMethod, maxDepth, snippetRadius);

            ObjectNode result = mapper.createObjectNode();
            result.put("targetMethod", impact.targetMethod());
            result.put("totalCallers", impact.totalCallers());
            result.put("affectedRepoCount", impact.affectedRepoCount());
            result.put("affectedEndpointCount", impact.affectedEndpointCount());
            result.put("truncated", impact.truncated());

            ArrayNode callersNode = result.putArray("callers");
            for (var c : impact.callers()) {
                ObjectNode cn = mapper.createObjectNode();
                cn.put("method", c.fullMethod());
                cn.put("shortRef", c.shortRef());
                cn.put("repoId", c.repoId());
                cn.put("repoName", c.repoName());
                cn.put("callType", c.callType() != null ? c.callType() : "CALL");
                if (c.lineNumber() != null) cn.put("lineNumber", c.lineNumber());
                cn.put("depth", c.depth());
                cn.put("isApiEndpoint", c.isEndpoint());
                if (c.endpointType() != null) cn.put("endpointType", c.endpointType());
                if (c.httpMethod() != null) cn.put("httpMethod", c.httpMethod());
                if (c.urlPath() != null) cn.put("urlPath", c.urlPath());
                if (c.callerReturnType() != null) cn.put("callerReturnType", c.callerReturnType());
                if (c.calleeActualReturnType() != null) cn.put("targetReturnType", c.calleeActualReturnType());
                if (c.callSiteSnippet() != null) cn.put("callSiteSnippet", c.callSiteSnippet());
                callersNode.add(cn);
            }

            if (impact.totalCallers() == 0) {
                result.put("message", "未发现任何调用方。可能该方法无外部调用，或相关仓库分析时配置了包前缀过滤。");
            }
            if (impact.truncated()) {
                result.put("hint", "结果因节点数上限被截断，部分深层调用方未列出，可针对关键分支再单独分析。");
            }

            appendAmbiguityWarnings(result, impact.warnings());

            logger.info("[MCP] getImpactWithSource method={} callers={} endpoints={}",
                    fullMethod, impact.totalCallers(), impact.affectedEndpointCount());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getImpactWithSource 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具：跨库影响分析（核心）────────────────────────────────────────────
    @Tool(name = "getCrossRepoImpact",
          description = "【跨项目影响分析】查找所有已分析仓库中，会调用到指定方法的入口点（Controller/MQ/定时任务）。" +
                        "与 getImpactAnalysis 不同，本工具会**跨多个仓库**向上递归追踪，找出其他项目对该方法的依赖。" +
                        "【强烈建议】修改被多个项目引用的公共方法（如基础库、共享 Service）之前，务必调用此工具，" +
                        "了解改动会波及哪些项目的哪些对外接口，避免跨项目连锁故障。" +
                        "fullMethod 格式：类全限定名:方法名(参数类型列表)。maxDepth 建议 5-10。")
    public String getCrossRepoImpact(
            @ToolParam(description = "被修改的方法完整签名，格式：类全限定名:方法名(参数类型列表)") String fullMethod,
            @ToolParam(description = "向上追踪的最大深度，建议 5-10，最大 15") int maxDepth) {
        try {
            CallGraphEngine.CrossRepoImpactDTO impact = callGraphEngine.getCrossRepoImpact(fullMethod, maxDepth);

            ObjectNode result = mapper.createObjectNode();
            result.put("targetMethod", impact.targetMethod());
            result.put("totalCallers", impact.totalCallers());
            result.put("affectedRepoCount", impact.affectedRepoCount());
            result.put("affectedEndpointCount", impact.affectedEndpointCount());
            result.put("truncated", impact.truncated());

            ArrayNode reposNode = result.putArray("affectedRepos");
            for (var group : impact.repoGroups()) {
                ObjectNode g = mapper.createObjectNode();
                g.put("repoId", group.repoId());
                g.put("repoName", group.repoName());

                ArrayNode epNode = g.putArray("affectedEndpoints");
                for (var ep : group.endpoints()) {
                    ObjectNode e = mapper.createObjectNode();
                    e.put("endpointType", ep.endpointType());
                    if (ep.httpMethod() != null) e.put("httpMethod", ep.httpMethod());
                    if (ep.urlPath() != null) e.put("urlPath", ep.urlPath());
                    e.put("method", ep.fullMethod());
                    e.put("shortRef", ep.shortRef());
                    epNode.add(e);
                }

                ArrayNode callersNode = g.putArray("callers");
                for (var c : group.callers()) {
                    ObjectNode cn = mapper.createObjectNode();
                    cn.put("method", c.fullMethod());
                    cn.put("shortRef", c.shortRef());
                    cn.put("callType", c.callType() != null ? c.callType() : "CALL");
                    if (c.lineNumber() != null) cn.put("lineNumber", c.lineNumber());
                    cn.put("depth", c.depth());
                    cn.put("isApiEndpoint", c.isEndpoint());
                    callersNode.add(cn);
                }
                reposNode.add(g);
            }

            if (impact.affectedRepoCount() == 0) {
                result.put("message", "未发现任何仓库调用此方法。可能原因：该方法无外部调用方，" +
                        "或相关仓库分析时配置了包前缀过滤（analyze.package.prefix）导致跨库调用边未被记录。");
            }

            // 同名签名歧义告警
            appendAmbiguityWarnings(result, impact.warnings());

            logger.info("[MCP] getCrossRepoImpact method={} repos={} endpoints={}",
                    fullMethod, impact.affectedRepoCount(), impact.affectedEndpointCount());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getCrossRepoImpact 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具：跨库调用链展开 ─────────────────────────────────────────────────
    @Tool(name = "getCrossRepoCallTree",
          description = "【跨项目调用链】展开指定方法的下游调用树，当某个被调用方法定义在**其他仓库**时，自动跨库续接展开。" +
                        "适合用于：某接口调用了其他项目提供的方法（如跨服务的本地依赖），想看完整的跨项目实现路径。" +
                        "节点的 crossesRepo=true 表示该调用跨越了仓库边界，external=true 表示是第三方库（不展开）。" +
                        "fullMethod 格式：类全限定名:方法名(参数类型列表)。maxDepth 建议 5-10。")
    public String getCrossRepoCallTree(
            @ToolParam(description = "入口方法完整签名，格式：类全限定名:方法名(参数类型列表)") String fullMethod,
            @ToolParam(description = "向下展开的最大深度，建议 5-10，最大 15") int maxDepth) {
        try {
            CallGraphEngine.CrossRepoTreeDTO tree = callGraphEngine.getCrossRepoCallTree(fullMethod, maxDepth);
            if (tree == null || tree.root() == null) {
                return "{\"error\": \"未找到方法: " + fullMethod + "\"}";
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("entryMethod", fullMethod);
            result.put("totalNodes", tree.totalNodes());
            result.put("maxDepth", tree.maxDepth());
            result.put("hasCycle", tree.hasCycle());
            result.put("truncated", tree.truncated());
            result.set("callTree", buildCrossRepoTreeNode(tree.root()));

            // 同名签名歧义告警
            appendAmbiguityWarnings(result, tree.warnings());

            logger.info("[MCP] getCrossRepoCallTree method={} nodes={}", fullMethod, tree.totalNodes());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getCrossRepoCallTree 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private ObjectNode buildCrossRepoTreeNode(CallGraphEngine.CrossRepoNodeDTO node) {
        ObjectNode n = mapper.createObjectNode();
        n.put("method", node.fullMethod());
        n.put("shortRef", node.shortRef());
        if (node.repoId() != null) n.put("repoId", node.repoId());
        if (node.repoName() != null) n.put("repoName", node.repoName());
        if (node.callType() != null) n.put("callType", node.callType());
        if (node.lineNumber() != null) n.put("lineNumber", node.lineNumber());
        if (node.crossesRepo()) n.put("crossesRepo", true);
        if (node.external()) n.put("external", true);
        if (node.ambiguous()) n.put("ambiguous", true);
        if (node.isRecursive()) n.put("recursive", true);
        if (node.isLazyLoad()) n.put("lazyLoad", true);
        if (node.children() != null && !node.children().isEmpty()) {
            ArrayNode children = n.putArray("children");
            for (var child : node.children()) {
                children.add(buildCrossRepoTreeNode(child));
            }
        }
        return n;
    }

    /** 把同名签名歧义告警写入结果 JSON，提醒 AI 跨库匹配可能不准确。 */
    private void appendAmbiguityWarnings(ObjectNode result, List<CallGraphEngine.AmbiguityWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) return;
        ObjectNode warn = result.putObject("ambiguityWarning");
        warn.put("message", "⚠️ 检测到以下方法签名在多个仓库存在完全相同的定义（同包名+类名+方法+入参）。" +
                "静态分析仅靠签名字符串匹配，无法确定调用真正指向哪个仓库的实现，" +
                "相关跨库调用链/影响范围可能存在误匹配，请人工确认。");
        ArrayNode arr = warn.putArray("ambiguousMethods");
        for (var w : warnings) {
            ObjectNode wn = mapper.createObjectNode();
            wn.put("method", w.fullMethod());
            ArrayNode locs = wn.putArray("definedIn");
            for (var loc : w.locations()) {
                ObjectNode l = mapper.createObjectNode();
                l.put("repoId", loc.repoId());
                l.put("repoName", loc.repoName());
                if (loc.filePath() != null) l.put("filePath", loc.filePath());
                locs.add(l);
            }
            arr.add(wn);
        }
    }

    // ── 工具 4：获取方法源码 ──────────────────────────────────────────────────

    @Tool(name = "getMethodSource",
          description = "获取指定方法的完整源码。" +
                        "如果方法是第三方库（如 Spring Framework、JDK 内置）则返回 SOURCE_NOT_FOUND。" +
                        "适合用于：阅读某个方法的具体实现，了解业务逻辑细节。")
    public String getMethodSource(
            @ToolParam(description = "仓库 ID") long repoId,
            @ToolParam(description = "方法完整签名") String fullMethod) {
        try {
            if (!repositoryExists(repoId)) {
                return "{\"error\": \"仓库不存在或未分析完成: " + repoId + "\"}";
            }
            String source = callGraphEngine.getMethodSource(repoId, fullMethod);
            if (source == null) {
                return "{\"found\": false, \"fullMethod\": \"" + fullMethod + "\", " +
                       "\"message\": \"SOURCE_NOT_FOUND: 可能是第三方库、框架生成代码或 JDK 方法\"}";
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("found", true);
            result.put("fullMethod", fullMethod);
            result.put("shortRef", extractShortRef(fullMethod));
            result.put("sourceCode", source);
            result.put("lineCount", source.split("\n").length);
            logger.info("[MCP] getMethodSource repoId={} method={}", repoId, fullMethod);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getMethodSource 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具 5：获取边界点（外部依赖）────────────────────────────────────────

    @Tool(name = "getBoundaries",
          description = "获取指定方法的外部依赖边界点，包括：DB 操作（INSERT/SELECT/UPDATE/DELETE）、" +
                        "HTTP 调用（RestTemplate/Feign/WebClient）、MQ 消息发送（Kafka/RocketMQ/RabbitMQ）、" +
                        "缓存操作（Redis）、gRPC 调用。" +
                        "适合用于：了解某个方法依赖哪些外部系统，排查分布式调用问题。")
    public String getBoundaries(
            @ToolParam(description = "仓库 ID") long repoId,
            @ToolParam(description = "方法完整签名") String fullMethod) {
        try {
            if (!repositoryExists(repoId)) {
                return "{\"error\": \"仓库不存在或未分析完成: " + repoId + "\"}";
            }
            List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, fullMethod);

            ObjectNode result = mapper.createObjectNode();
            result.put("fullMethod", fullMethod);
            result.put("shortRef", extractShortRef(fullMethod));
            result.put("boundaryCount", boundaries.size());

            ArrayNode bArray = result.putArray("boundaries");
            for (BoundaryEntity b : boundaries) {
                ObjectNode bNode = mapper.createObjectNode();
                bNode.put("type", b.getBoundaryType());
                if (b.getLineNumber() != null) bNode.put("lineNumber", b.getLineNumber());
                if (b.getContext() != null) bNode.put("context", b.getContext().split("\n")[0]);
                if (b.getCalleeMethod() != null) bNode.put("calleeMethod", b.getCalleeMethod());
                bArray.add(bNode);
            }

            if (boundaries.isEmpty()) {
                result.put("message", "该方法无外部依赖边界点（纯内存计算）");
            }
            logger.info("[MCP] getBoundaries repoId={} method={} count={}", repoId, fullMethod, boundaries.size());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] getBoundaries 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具 6：语义搜索 ──────────────────────────────────────────────────────

    @Tool(name = "semanticSearch",
          description = "用自然语言语义搜索相关的 Java 方法或 API 入口。" +
                        "例如：\"用户签到积分计算\"、\"订单创建接口\"、\"支付回调处理\"。" +
                        "优先使用向量语义搜索，不可用时降级为关键词搜索。" +
                        "适合用于：不知道方法全名时，通过业务描述找到相关代码入口。")
    public String semanticSearch(
            @ToolParam(description = "仓库 ID，传 0 表示搜索所有仓库") long repoId,
            @ToolParam(description = "搜索关键词或业务描述，支持中英文") String query,
            @ToolParam(description = "返回结果数量，建议 5-10") int topK) {
        try {
            List<Long> repoIds = repoId == 0
                    ? repositoryRepo.findAll().stream()
                        .filter(r -> "READY".equals(r.getStatus()))
                        .map(RepositoryEntity::getId).collect(Collectors.toList())
                    : List.of(repoId);

            if (repoIds.isEmpty()) {
                return "{\"results\": [], \"message\": \"暂无已分析的仓库\"}";
            }

            int k = Math.min(topK, 20);
            List<VectorStoreService.SearchResult> vectorResults = List.of();
            boolean usedVector = false;

            // 优先向量搜索
            if (vectorStoreService.isAvailable()) {
                try {
                    float[] queryVector = embeddingService.embed(query);
                    if (queryVector.length > 0) {
                        vectorResults = vectorStoreService.search(queryVector, repoIds, k);
                        usedVector = true;
                    }
                } catch (Exception e) {
                    logger.warn("[MCP] 向量搜索失败，降级关键词搜索: {}", e.getMessage());
                }
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("query", query);
            result.put("usedVectorSearch", usedVector);
            ArrayNode resultsArray = result.putArray("results");

            if (usedVector && !vectorResults.isEmpty()) {
                for (var vr : vectorResults) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("fullMethod", vr.fullMethod());
                    r.put("shortRef", extractShortRef(vr.fullMethod()));
                    r.put("score", vr.score());
                    r.put("repoId", vr.repoId());
                    // 获取仓库名
                    repositoryRepo.findById(vr.repoId()).ifPresent(repo -> r.put("repoName", repo.getName()));
                    if (vr.summary() != null && !vr.summary().isBlank()) r.put("summary", vr.summary());
                    resultsArray.add(r);
                }
            } else {
                // 降级：关键词搜索 api_endpoints
                String lowerQuery = query.toLowerCase();
                for (Long rid : repoIds) {
                    apiEndpointRepo.findByRepoId(rid).stream()
                            .filter(ep -> matchKeyword(ep, lowerQuery))
                            .limit(k)
                            .forEach(ep -> {
                                ObjectNode r = mapper.createObjectNode();
                                r.put("fullMethod", ep.getFullMethod());
                                r.put("shortRef", extractShortRef(ep.getFullMethod()));
                                r.put("endpointType", ep.getEndpointType());
                                if (ep.getHttpMethod() != null) r.put("httpMethod", ep.getHttpMethod());
                                if (ep.getUrlPath() != null) r.put("urlPath", ep.getUrlPath());
                                r.put("repoId", rid);
                                repositoryRepo.findById(rid).ifPresent(repo -> r.put("repoName", repo.getName()));
                                resultsArray.add(r);
                            });
                }
            }

            result.put("resultCount", resultsArray.size());
            logger.info("[MCP] semanticSearch query='{}' results={} vector={}", query, resultsArray.size(), usedVector);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] semanticSearch 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 工具 7：列出仓库所有 API 入口点 ──────────────────────────────────────

    @Tool(name = "listApiEndpoints",
          description = "列出指定仓库的所有 API 入口点，包括：HTTP Controller、Kafka/RocketMQ 消费者、" +
                        "定时任务（@Scheduled）、gRPC 服务方法。" +
                        "适合用于：了解某个仓库对外暴露了哪些接口，作为代码阅读的起点。")
    public String listApiEndpoints(
            @ToolParam(description = "仓库 ID") long repoId,
            @ToolParam(description = "可选的过滤类型：CONTROLLER / KAFKA / ROCKETMQ / GRPC / SCHEDULED，留空返回全部") String endpointType) {
        try {
            if (!repositoryExists(repoId)) {
                return "{\"error\": \"仓库不存在或未分析完成: " + repoId + "\"}";
            }
            List<ApiEndpointEntity> endpoints = apiEndpointRepo.findByRepoId(repoId);
            if (endpointType != null && !endpointType.isBlank()) {
                endpoints = endpoints.stream()
                        .filter(e -> endpointType.equalsIgnoreCase(e.getEndpointType()))
                        .collect(Collectors.toList());
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("repoId", repoId);
            result.put("total", endpoints.size());
            ArrayNode epArray = result.putArray("endpoints");
            for (ApiEndpointEntity ep : endpoints) {
                ObjectNode e = mapper.createObjectNode();
                e.put("endpointType", ep.getEndpointType());
                if (ep.getHttpMethod() != null) e.put("httpMethod", ep.getHttpMethod());
                if (ep.getUrlPath() != null) e.put("urlPath", ep.getUrlPath());
                e.put("fullMethod", ep.getFullMethod());
                e.put("shortRef", extractShortRef(ep.getFullMethod()));
                if (ep.getClassName() != null) {
                    String shortCls = ep.getClassName().contains(".")
                            ? ep.getClassName().substring(ep.getClassName().lastIndexOf('.') + 1)
                            : ep.getClassName();
                    e.put("className", shortCls);
                }
                epArray.add(e);
            }
            logger.info("[MCP] listApiEndpoints repoId={} type={} count={}", repoId, endpointType, endpoints.size());
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[MCP] listApiEndpoints 失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private boolean repositoryExists(long repoId) {
        return repositoryRepo.findById(repoId)
                .map(r -> "READY".equals(r.getStatus()))
                .orElse(false);
    }

    private String extractShortRef(String fullMethod) {
        int colonIdx = fullMethod.lastIndexOf(':');
        String cls = colonIdx > 0 ? fullMethod.substring(0, colonIdx) : fullMethod;
        String method = colonIdx > 0 ? fullMethod.substring(colonIdx + 1) : "";
        int paren = method.indexOf('(');
        if (paren > 0) method = method.substring(0, paren);
        int dotIdx = cls.lastIndexOf('.');
        String shortCls = dotIdx >= 0 ? cls.substring(dotIdx + 1) : cls;
        return shortCls + "." + method;
    }

    private boolean matchKeyword(ApiEndpointEntity ep, String lowerQuery) {
        String[] words = lowerQuery.split("\\s+");
        String text = ((ep.getUrlPath() != null ? ep.getUrlPath() : "") + " "
                + (ep.getClassName() != null ? ep.getClassName() : "") + " "
                + ep.getFullMethod()).toLowerCase();
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private ObjectNode buildCallTreeNode(CallGraphEngine.CallTreeNodeDTO node) {
        ObjectNode n = mapper.createObjectNode();
        n.put("method", node.fullMethod());
        n.put("shortRef", extractShortRef(node.fullMethod()));
        if (node.callType() != null) n.put("callType", node.callType());
        if (node.lineNumber() != null) n.put("lineNumber", node.lineNumber());
        n.put("isRecursive", node.isRecursive());
        n.put("isLazyLoad", node.isLazyLoad());

        // 边界点
        if (node.boundaries() != null && !node.boundaries().isEmpty()) {
            ArrayNode bArr = n.putArray("boundaries");
            for (var b : node.boundaries()) {
                ObjectNode bNode = mapper.createObjectNode();
                bNode.put("type", b.boundaryType());
                if (b.context() != null) bNode.put("context", b.context().split("\n")[0]);
                bArr.add(bNode);
            }
        }

        // 子节点
        if (node.children() != null && !node.children().isEmpty()) {
            ArrayNode children = n.putArray("children");
            for (var child : node.children()) {
                children.add(buildCallTreeNode(child));
            }
        }
        return n;
    }
}
