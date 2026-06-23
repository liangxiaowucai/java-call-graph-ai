package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.*;
import com.adrninistrator.javacg2.platform.repository.*;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CallGraphEngineImpl implements CallGraphEngine {

    private static final Logger logger = LoggerFactory.getLogger(CallGraphEngineImpl.class);
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int LAZY_LOAD_DEFAULT_DEPTH = 3;
    private static final int MAX_TOTAL_NODES = 500;
    // 分析场景（请求链/报告/MCP）全展开时的节点上限，远大于展示场景，确保异步/深层调用不被截断
    private static final int FULL_EXPAND_MAX_NODES = 8000;

    // 源码未找到的类名去重缓存（避免同一类名重复打印 DEBUG 日志）
    private final Set<String> sourceNotFoundClasses = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // 源码文件定位缓存：repoRoot|relativePath -> Path（缺失用 NOT_FOUND 标记，避免重复全盘 Files.walk）
    private final Map<String, Path> sourceFileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Path SOURCE_NOT_FOUND = Path.of("__JCG_SOURCE_NOT_FOUND__");
    // 枚举常量值缓存：repoPath|EnumClass.CONSTANT -> 值（缺失用空串标记）
    private final Map<String, String> enumValueCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ApiEndpointRepo apiEndpointRepo;
    private final CallGraphRepo callGraphRepo;
    private final BoundaryRepo boundaryRepo;
    private final ChunkRepo chunkRepo;
    private final RepositoryRepo repositoryRepo;
    private final SystemConfigRepo systemConfigRepo;
    private final RepoConfigRepo repoConfigRepo;

    public CallGraphEngineImpl(ApiEndpointRepo apiEndpointRepo, CallGraphRepo callGraphRepo,
                                BoundaryRepo boundaryRepo, ChunkRepo chunkRepo,
                                RepositoryRepo repositoryRepo, SystemConfigRepo systemConfigRepo,
                                RepoConfigRepo repoConfigRepo) {
        this.apiEndpointRepo = apiEndpointRepo;
        this.callGraphRepo = callGraphRepo;
        this.boundaryRepo = boundaryRepo;
        this.chunkRepo = chunkRepo;
        this.repositoryRepo = repositoryRepo;
        this.systemConfigRepo = systemConfigRepo;
        this.repoConfigRepo = repoConfigRepo;
    }

    @Override
    public List<EntryPointDTO> getEntryPoints(Long repoId) {
        return apiEndpointRepo.findByRepoId(repoId).stream()
                .map(e -> new EntryPointDTO(e.getId(), e.getEndpointType(), e.getHttpMethod(),
                        e.getUrlPath(), e.getFullMethod(), e.getClassName()))
                .collect(Collectors.toList());
    }

    @Override
    public CallTreeDTO expandCallTree(Long repoId, String entryMethod, int maxDepth) {
        // 默认全展开：不再懒加载，所有调用链一次性展开到底
        return expandCallTree(repoId, entryMethod, maxDepth, true);
    }

    @Override
    public CallTreeDTO expandCallTree(Long repoId, String entryMethod, int maxDepth, boolean fullExpand) {
        // 全展开模式忽略深度限制，确保异步/深层/lambda 调用完整展开
        if (fullExpand) maxDepth = Integer.MAX_VALUE;
        else if (maxDepth <= 0) maxDepth = DEFAULT_MAX_DEPTH;

        // 从仓库级配置读包前缀（支持多个，逗号分隔）
        String packagePrefixRaw = repoConfigRepo.findByRepoIdAndConfigKey(repoId, "analyze.package.prefix")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        // 解析为前缀列表
        List<String> packagePrefixes = new ArrayList<>();
        if (packagePrefixRaw != null) {
            for (String p : packagePrefixRaw.split("[,;\\s]+")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) packagePrefixes.add(trimmed);
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        int[] nodeCount = {0};
        boolean[] hasCycle = {false};

        // 预先收集仓库信息（用于生成详细的歧义警告）
        Map<Long, String> repoNames = new HashMap<>();
        repositoryRepo.findAll().forEach(r -> repoNames.put(r.getId(), r.getName()));

        // ── 性能优化：全展开模式下批量预加载整个仓库数据到内存 ──
        // 将 N×3 次逐节点 DB 查询优化为 3 次全量查询 + 内存 HashMap 查找
        RepoDataCache cache = null;
        if (fullExpand) {
            long t0 = System.currentTimeMillis();
            cache = new RepoDataCache(repoId);
            logger.info("[调用树] 批量预加载仓库数据 repoId={}, 耗时={}ms, 调用边={}, 边界={}, chunk={}",
                    repoId, System.currentTimeMillis() - t0,
                    cache.callGraphMap.values().stream().mapToInt(List::size).sum(),
                    cache.boundaryMap.values().stream().mapToInt(List::size).sum(),
                    cache.chunkMap.size());
        }

        // 先构建调用树（ambiguous 字段初始为 false）
        CallTreeNodeDTO root = buildNodeLazy(repoId, entryMethod, packagePrefixes,
                visited, expanded, 0, maxDepth, nodeCount, hasCycle, fullExpand, cache);

        // 构建完成后，收集歧义信息
        Map<String, AmbiguityWarning> ambiguities = new LinkedHashMap<>();
        collectAmbiguities(root, repoNames, ambiguities);
        
        List<AmbiguityWarning> warnings = new ArrayList<>(ambiguities.values());
        
        if (!warnings.isEmpty()) {
            logger.info("[调用树] method={} 节点={} 歧义={}", entryMethod, nodeCount[0], warnings.size());
            // 标记歧义节点（返回新的树结构）
            Set<String> ambiguousMethods = ambiguities.keySet();
            root = markAmbiguousNodes(root, ambiguousMethods);
        }

        return new CallTreeDTO(root, nodeCount[0], maxDepth, hasCycle[0], warnings);
    }
    
    /**
     * 收集歧义方法（遍历整棵树，检测所有方法）
     */
    private void collectAmbiguities(CallTreeNodeDTO node, Map<Long, String> repoNames, 
                                    Map<String, AmbiguityWarning> ambiguities) {
        if (node == null) return;
        
        // 检查当前节点是否有歧义
        addAmbiguityIfAny(node.fullMethod(), repoNames, ambiguities);
        
        // 递归检查子节点
        if (node.children() != null) {
            for (CallTreeNodeDTO child : node.children()) {
                collectAmbiguities(child, repoNames, ambiguities);
            }
        }
    }
    
    /**
     * 标记歧义节点（因为 Record 不可变，需要创建新的 DTO 实例）
     */
    private CallTreeNodeDTO markAmbiguousNodes(CallTreeNodeDTO node, Set<String> ambiguousMethods) {
        if (node == null) return null;
        
        boolean isAmbiguous = ambiguousMethods.contains(node.fullMethod());
        
        // 递归标记子节点
        List<CallTreeNodeDTO> markedChildren = null;
        if (node.children() != null && !node.children().isEmpty()) {
            markedChildren = node.children().stream()
                    .map(child -> markAmbiguousNodes(child, ambiguousMethods))
                    .collect(Collectors.toList());
        } else {
            markedChildren = node.children();
        }
        
        // 如果当前节点是歧义的，或者子节点被修改了，创建新的 DTO
        if (isAmbiguous || markedChildren != node.children()) {
            return new CallTreeNodeDTO(
                    node.fullMethod(), node.className(), node.methodName(),
                    node.callType(), node.lineNumber(),
                    node.boundaries(), markedChildren,
                    node.isRecursive(), node.isLazyLoad(), isAmbiguous,
                    node.constants(), node.exceptions());
        }
        
        return node;
    }

    /**
     * 逐层按需查询构建调用树节点。
     * 每个节点只查自己的直接子节点（按 callerMethod 查 call_graph 表），不全量加载。
     * visited 为「当前路径集」用于环检测（进入时 add、返回前 remove）；
     * expanded 为「全局已完整展开集」，避免菱形图中同一子树被多条路径反复展开撞上限。
     */
    private CallTreeNodeDTO buildNodeLazy(Long repoId, String fullMethod, List<String> packagePrefixes,
                                           Set<String> visited, Set<String> expanded, int depth, int maxDepth,
                                           int[] nodeCount, boolean[] hasCycle, boolean fullExpand,
                                           RepoDataCache cache) {
        nodeCount[0]++;
        int maxNodes = fullExpand ? FULL_EXPAND_MAX_NODES : MAX_TOTAL_NODES;

        // 递归检测（当前路径上已出现 → 成环）
        if (visited.contains(fullMethod)) {
            hasCycle[0] = true;
            return new CallTreeNodeDTO(fullMethod, extractClassName(fullMethod),
                    extractMethodName(fullMethod), null, null,
                    List.of(), List.of(), true, false, false, null, null);
        }

        // 深度限制 或 节点总数超限 → 标记懒加载
        if (depth >= maxDepth || nodeCount[0] > maxNodes) {
            return new CallTreeNodeDTO(fullMethod, extractClassName(fullMethod),
                    extractMethodName(fullMethod), null, null,
                    List.of(), List.of(), false, true, false, null, null);
        }

        // 已在别处完整展开过（菱形汇聚点）→ 折叠为懒加载，不重复展开
        if (expanded.contains(fullMethod)) {
            return new CallTreeNodeDTO(fullMethod, extractClassName(fullMethod),
                    extractMethodName(fullMethod), null, null,
                    List.of(), List.of(), false, true, false, null, null);
        }

        visited.add(fullMethod);

        // 查询当前方法的直接调用（优先使用缓存）
        List<CallGraphEntity> callees = (cache != null
                ? cache.getCallees(fullMethod)
                : callGraphRepo.findByRepoIdAndCallerMethod(repoId, fullMethod))
                .stream()
                .filter(c -> c.getEnabled() != null && c.getEnabled())
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .filter(c -> !isBoilerplate(c.getCalleeMethod()))
                .filter(c -> packagePrefixes.isEmpty() || packagePrefixes.stream().anyMatch(p -> c.getCalleeMethod().startsWith(p)))
                .collect(Collectors.toList());
        
        // 调试日志
        if (fullMethod.contains("queryClassProgressInfo")) {
            logger.info("[调用树构建] {} 的子调用数: {}", fullMethod, callees.size());
            callees.forEach(c -> logger.info("  - {} ({})", c.getCalleeMethod(), c.getCallType()));
        }

        // 接口/抽象方法桥接：自身无下游调用边时，接到实现类的同签名方法继续展开
        List<String> implTargets = callees.isEmpty()
                ? resolveImplementations(repoId, fullMethod).stream()
                    .filter(m -> packagePrefixes.isEmpty() || packagePrefixes.stream().anyMatch(m::startsWith))
                    .collect(Collectors.toList())
                : List.of();

        // 获取边界点 —— 只保留真正的外部 I/O 边界（HTTP/RPC/DB/CACHE/MQ）。
        // EXCEPTION 由干净的 exceptions 字段展示；SERIALIZATION/TRANSACTION 不属于外部调用，避免污染计数与 AI 判断。
        Set<String> ioBoundaryTypes = Set.of("HTTP", "RPC", "GRPC", "DB", "CACHE", "REDIS", "MQ");
        List<BoundaryDTO> boundaries = new ArrayList<>((cache != null
                ? cache.getBoundaries(fullMethod)
                : boundaryRepo.findByRepoIdAndFullMethod(repoId, fullMethod))
                .stream()
                .filter(b -> b.getBoundaryType() != null && ioBoundaryTypes.contains(b.getBoundaryType()))
                .map(b -> new BoundaryDTO(b.getBoundaryType(), b.getLineNumber(), b.getContext()))
                .collect(Collectors.toList()));

        // 获取干净的结构化数据（常量/异常/解析后的URL）—— 来自 chunks 表的专用列，而非污染的 call_summary 搜索索引
        String constants = null;
        String exceptions = null;
        try {
            ChunkEntity chunk = (cache != null
                    ? cache.getChunk(fullMethod)
                    : chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).stream().findFirst().orElse(null));
            if (chunk != null) {
                constants = chunk.getConstants();   // 干净的字符串常量（换行分隔）
                exceptions = chunk.getErrorCodes();  // 异常区改为展示业务错误码+消息（code+msg）

                // 解析出的外部调用 URL（JSON: [{url,configKey,field}]）→ 合成 HTTP 边界
                String resolvedUrls = chunk.getResolvedUrls();
                if (resolvedUrls != null && !resolvedUrls.isBlank()) {
                    Set<String> existingHttpCtx = boundaries.stream()
                            .filter(b -> "HTTP".equals(b.boundaryType()) && b.context() != null)
                            .map(BoundaryDTO::context)
                            .collect(Collectors.toSet());
                    try {
                        com.fasterxml.jackson.databind.JsonNode arr = jsonMapper.readTree(resolvedUrls);
                        if (arr.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode u : arr) {
                                String url = u.path("url").asText("");
                                if (url.isBlank()) continue;
                                String cfgKey = u.path("configKey").asText(null);
                                String ctx = "📌 URL: " + url + (cfgKey != null ? "  (${" + cfgKey + "})" : "");
                                if (existingHttpCtx.stream().noneMatch(c -> c.contains(url))) {
                                    boundaries.add(new BoundaryDTO("HTTP", null, ctx));
                                }
                            }
                        }
                    } catch (Exception ex) {
                        logger.debug("[URL解析] JSON 解析失败: {}", fullMethod);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[结构化数据加载] 失败: {}", fullMethod);
        }
        
        // 调试日志
        if (!boundaries.isEmpty()) {
            logger.debug("[边界加载] {} 有 {} 个边界: {}", fullMethod, boundaries.size(), 
                boundaries.stream().map(BoundaryDTO::boundaryType).collect(Collectors.joining(", ")));
        }

        // 超过懒加载深度 → 只返回当前节点，子节点标记懒加载（全展开模式下禁用，确保深层/异步调用完整）
        if (!fullExpand && depth >= LAZY_LOAD_DEFAULT_DEPTH && nodeCount[0] > LAZY_LOAD_DEFAULT_DEPTH * 10) {
            List<CallTreeNodeDTO> lazyChildren = new ArrayList<>(callees.stream()
                    .map(c -> new CallTreeNodeDTO(c.getCalleeMethod(), extractClassName(c.getCalleeMethod()),
                                extractMethodName(c.getCalleeMethod()), c.getCallType(), c.getLineNumber(),
                                List.of(), List.of(), false, true, false, null, null))
                    .collect(Collectors.toList()));
            for (String impl : implTargets) {
                lazyChildren.add(new CallTreeNodeDTO(impl, extractClassName(impl), extractMethodName(impl),
                        "IMPL", null, List.of(), List.of(), false, true, false, null, null));
            }
            visited.remove(fullMethod);
            expanded.add(fullMethod);
            return new CallTreeNodeDTO(fullMethod, extractClassName(fullMethod),
                    extractMethodName(fullMethod), null, null,
                    boundaries, lazyChildren, false, false, false, constants, exceptions);
        }

        // 递归展开子节点（visited 共享，进入子节点前已 add 当前节点，返回后统一 remove）
        List<CallTreeNodeDTO> children = new ArrayList<>();
        for (CallGraphEntity callee : callees) {
            CallTreeNodeDTO child = buildNodeLazy(repoId, callee.getCalleeMethod(), packagePrefixes,
                    visited, expanded, depth + 1, maxDepth, nodeCount, hasCycle, fullExpand, cache);
            children.add(new CallTreeNodeDTO(child.fullMethod(), child.className(), child.methodName(),
                    callee.getCallType(), callee.getLineNumber(),
                    child.boundaries(), child.children(), child.isRecursive(), child.isLazyLoad(), child.ambiguous(),
                    child.constants(), child.exceptions()));
        }
        // 桥接的实现方法以合成 IMPL 边接入，递归展开其方法体
        for (String impl : implTargets) {
            CallTreeNodeDTO child = buildNodeLazy(repoId, impl, packagePrefixes,
                    visited, expanded, depth + 1, maxDepth, nodeCount, hasCycle, fullExpand, cache);
            children.add(new CallTreeNodeDTO(child.fullMethod(), child.className(), child.methodName(),
                    "IMPL", null,
                    child.boundaries(), child.children(), child.isRecursive(), child.isLazyLoad(), child.ambiguous(),
                    child.constants(), child.exceptions()));
        }

        visited.remove(fullMethod);  // 离开当前路径，允许其它分支再次经过（环检测仍由 expanded 兜底防重复展开）
        expanded.add(fullMethod);
        return new CallTreeNodeDTO(fullMethod, extractClassName(fullMethod),
                extractMethodName(fullMethod), null, null,
                boundaries, children, false, false, false, constants, exceptions);
    }

    /** 过滤构造方法、setter/getter 等非业务方法 */
    private boolean isBoilerplate(String calleeMethod) {
        if (calleeMethod.contains(":<init>(") || calleeMethod.contains(":<clinit>(")) return true;
        String methodName = extractMethodName(calleeMethod);
        if ("equals".equals(methodName) || "hashCode".equals(methodName) || "toString".equals(methodName)) return true;

        // 仅按签名特征识别真正的访问器，避免误杀 getDetail(Long)/getById(Long) 这类业务方法：
        // 真正的 getter/is 访问器是无参的 getXxx()/isXxx()；真正的 setter 是单参的 setXxx(one)。
        String params = extractParams(calleeMethod);
        if (params == null) return false;
        boolean noArg = params.isEmpty();
        boolean singleArg = !noArg && !params.contains(",");
        if (noArg && (methodName.startsWith("get") || methodName.startsWith("is"))) return true;
        if (singleArg && methodName.startsWith("set")) return true;
        return false;
    }

    /** 提取方法签名括号内的入参字符串；无括号返回 null，无参返回 "" */
    private String extractParams(String fullMethod) {
        int open = fullMethod.indexOf('(');
        int close = fullMethod.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        return fullMethod.substring(open + 1, close).trim();
    }

    /**
     * 接口/抽象方法 → 实现方法桥接。
     * Java 中接口方法必有实现类实现（除非空体），但调用边只记录到接口（callType=INT/ITF），
     * 实现关系单独以 IMPLEMENTS/EXTENDS 边（类级）存储，实现体的下游 caller 是「实现类:方法」。
     * 本方法用实现关系把接口节点接到实现类的同签名方法，使调用链能继续往下展开。
     *
     * @return 真实存在的实现方法 fullMethod 列表（无实现/空体则为空）
     */
    private List<String> resolveImplementations(Long repoId, String interfaceFullMethod) {
        int colonIdx = interfaceFullMethod.lastIndexOf(':');
        if (colonIdx <= 0) return List.of();
        String interfaceClass = interfaceFullMethod.substring(0, colonIdx);
        String methodSig = interfaceFullMethod.substring(colonIdx + 1);

        // 查谁 IMPLEMENTS/EXTENDS 了该接口/抽象类（这些边里 callee=接口类名、caller=子类名）
        List<CallGraphEntity> relations = callGraphRepo.findByRepoIdAndCalleeMethod(repoId, interfaceClass).stream()
                .filter(c -> "IMPLEMENTS".equals(c.getCallType()) || "EXTENDS".equals(c.getCallType()))
                .collect(Collectors.toList());
        if (relations.isEmpty()) return List.of();

        List<String> impls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CallGraphEntity rel : relations) {
            String implClass = rel.getCallerMethod();
            String candidate = implClass + ":" + methodSig;
            if (!seen.add(candidate)) continue;
            // 仅保留真实存在且有方法体的实现：实现体作为 caller 出现过，或 chunk 表有定义
            boolean hasBody = !callGraphRepo.findByRepoIdAndCallerMethod(repoId, candidate).isEmpty()
                    || chunkRepo.findByRepoIdAndFullMethod(repoId, candidate).isPresent();
            if (hasBody) impls.add(candidate);
        }
        return impls;
    }

    @Override
    public List<CallerDTO> getCallers(Long repoId, String fullMethod, int depth) {
        if (depth <= 0) depth = 1;
        List<CallerDTO> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(fullMethod);
        collectCallers(repoId, fullMethod, 1, depth, visited, result);
        return result;
    }

    /** 向上递归收集调用方，按层级展开，visited 去重，节点上限兜底。 */
    private void collectCallers(Long repoId, String fullMethod, int depth, int maxDepth,
                                Set<String> visited, List<CallerDTO> result) {
        if (depth > maxDepth || result.size() >= MAX_TOTAL_NODES) return;

        List<CallGraphEntity> edges = callGraphRepo.findByRepoIdAndCalleeMethod(repoId, fullMethod).stream()
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .collect(Collectors.toList());

        for (CallGraphEntity c : edges) {
            String caller = c.getCallerMethod();
            if (!visited.add(caller)) continue; // 已访问（环或多路径汇聚）→ 跳过
            result.add(new CallerDTO(caller, extractClassName(caller), c.getCallType(), c.getLineNumber(), depth));
            if (result.size() >= MAX_TOTAL_NODES) return;
            // 继续向上追溯该调用方的调用方
            collectCallers(repoId, caller, depth + 1, maxDepth, visited, result);
        }
    }

    @Override
    public List<CallerDTO> getCallees(Long repoId, String fullMethod, int depth) {
        List<CallerDTO> result = callGraphRepo.findByRepoIdAndCallerMethod(repoId, fullMethod).stream()
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .map(c -> new CallerDTO(c.getCalleeMethod(), extractClassName(c.getCalleeMethod()),
                        c.getCallType(), c.getLineNumber(), 1))
                .collect(Collectors.toList());
        // 接口/抽象方法桥接：自身无下游时，返回其实现类的同签名方法
        if (result.isEmpty()) {
            for (String impl : resolveImplementations(repoId, fullMethod)) {
                result.add(new CallerDTO(impl, extractClassName(impl), "IMPL", null, 1));
            }
        }
        return result;
    }

    @Override
    public String getMethodSource(Long repoId, String fullMethod) {
        SourceResult r = getMethodSourceWithLine(repoId, fullMethod);
        return r == null ? null : r.source;
    }

    /** 源码片段 + 该片段在文件中的真实起始行号（1-based）+ 方法签名所在行号 */
    private static final class SourceResult {
        final String source;
        final int startLine;       // 片段首行对应的文件行号
        final int signatureLine;   // 方法签名/声明所在的文件行号（用于高亮定位）
        SourceResult(String source, int startLine, int signatureLine) {
            this.source = source; this.startLine = startLine; this.signatureLine = signatureLine;
        }
    }

    private SourceResult getMethodSourceWithLine(Long repoId, String fullMethod) {
        Optional<ChunkEntity> chunkOpt = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod);

        String className;
        Integer startLine = null, endLine = null;

        if (chunkOpt.isPresent()) {
            ChunkEntity chunk = chunkOpt.get();
            className = chunk.getClassName();
            startLine = chunk.getStartLine();
            endLine = chunk.getEndLine();
        } else {
            int colonIdx = fullMethod.lastIndexOf(':');
            className = colonIdx > 0 ? fullMethod.substring(0, colonIdx) : null;
            logger.warn("chunk 表未找到方法: {}，尝试从类名查找源码", fullMethod);
        }

        if (className == null) return null;

        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return null;

        String topLevelClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relativePath = topLevelClass.replace('.', '/') + ".java";

        Path sourcePath = findSourceFile(Path.of(repo.getLocalPath()), relativePath);
        if (sourcePath == null) {
            // gRPC 生成类、MapStruct、其他仓库依赖等找不到源码是正常的（同一类名只打一次）
            if (sourceNotFoundClasses.add(relativePath)) {
                logger.debug("未找到源码文件: {} (类名: {})", relativePath, className);
            }
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(sourcePath);
            if (startLine != null && endLine != null && startLine > 0 && endLine > 0) {
                // 有精确行号（通常是实现类方法）：向上多取几行，确保包含方法签名、注解、Javadoc
                int sigLine = startLine;
                int start = Math.max(0, startLine - 10);
                // 从 start 向下找到方法签名或注解开始的位置
                for (int i = start; i < startLine - 1 && i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.startsWith("/**") || trimmed.startsWith("@") || trimmed.startsWith("public ")
                            || trimmed.startsWith("private ") || trimmed.startsWith("protected ")) {
                        start = i;
                        break;
                    }
                }
                // endLine 是字节码最后行号，不一定是方法体 } 行；向后扫描花括号找到真正的方法结束
                int end = endLine;
                int braceDepth = 0;
                boolean entered = false;
                for (int i = start; i < lines.size() && i < endLine + 50; i++) {
                    String l = lines.get(i);
                    for (char ch : l.toCharArray()) {
                        if (ch == '{') { braceDepth++; entered = true; }
                        else if (ch == '}') { braceDepth--; }
                    }
                    if (entered && braceDepth <= 0) { end = i + 1; break; }
                }
                end = Math.min(lines.size(), end);
                return new SourceResult(String.join("\n", lines.subList(start, end)), start + 1, sigLine);
            }

            // 无精确行号（接口/抽象方法）：不要返回整个文件。
            // 1) 优先桥接到实现类方法，返回真正有方法体的实现源码
            for (String impl : resolveImplementations(repoId, fullMethod)) {
                SourceResult implSrc = getMethodSourceWithLine(repoId, impl);
                if (implSrc != null) return implSrc;
            }
            // 2) 没有可用实现：在接口文件里定位该方法的声明，截取其 Javadoc + 声明，而非整个文件
            SourceResult decl = extractDeclaration(lines, fullMethod);
            if (decl != null) return decl;
            // 3) 兜底：返回整个文件（极少数情况）
            return new SourceResult(String.join("\n", lines), 1, 1);
        } catch (IOException e) {
            logger.error("读取源码失败: {}", sourcePath, e);
            return null;
        }
    }

    /**
     * 在源码文件里按方法名 + 入参个数定位某个方法/声明，截取其上方 Javadoc/注解到声明结束的片段。
     * 用于接口、抽象方法等没有字节码行号、但能在源码里找到声明的情况。
     */
    private SourceResult extractDeclaration(List<String> lines, String fullMethod) {
        String methodName = extractMethodName(fullMethod);
        String params = extractParams(fullMethod);
        int paramCount = (params == null || params.isEmpty()) ? 0 : params.split(",").length;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            // 粗匹配：包含 "方法名(" 且不是调用（声明行通常以类型/修饰符开头，不以 . 或 = 等接调用）
            int idx = trimmed.indexOf(methodName + "(");
            if (idx < 0) continue;
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            // 排除明显的方法调用：前面紧跟 '.' 或 'new '
            if (idx > 0) {
                char prev = trimmed.charAt(idx - 1);
                if (prev == '.' ) continue;
            }
            // 校验入参个数大致匹配（声明可能跨行，这里只在同一行括号闭合时校验）
            int open = trimmed.indexOf('(', idx);
            int close = trimmed.indexOf(')', open);
            if (open >= 0 && close > open) {
                String inside = trimmed.substring(open + 1, close).trim();
                int cnt = inside.isEmpty() ? 0 : inside.split(",").length;
                if (cnt != paramCount) continue;
            }

            // 向上收集紧邻的 Javadoc / 注解
            int start = i;
            for (int j = i - 1; j >= 0; j--) {
                String t = lines.get(j).trim();
                if (t.isEmpty()) { break; }
                if (t.endsWith("*/") || t.startsWith("*") || t.startsWith("/**") || t.startsWith("//") || t.startsWith("@")) {
                    start = j;
                } else {
                    break;
                }
            }
            // 向下到声明结束（分号或 '{'），最多看 20 行
            int end = i;
            for (int j = i; j < lines.size() && j < i + 20; j++) {
                String t = lines.get(j).trim();
                end = j;
                if (t.endsWith(";") || t.endsWith("{")) break;
            }
            return new SourceResult(String.join("\n", lines.subList(start, end + 1)), start + 1, i + 1);
        }
        return null;
    }

    @Override
    public MethodSourceDTO getMethodSourceDetail(Long repoId, String fullMethod, String entryMethod) {
        SourceResult srcResult = getMethodSourceWithLine(repoId, fullMethod);
        if (srcResult == null) return null;
        String source = srcResult.source;

        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        String repoPath = repo != null ? repo.getLocalPath() : null;

        // 提取方法签名
        String methodSignature = null;
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("(") && (trimmed.startsWith("public ") || trimmed.startsWith("private ")
                    || trimmed.startsWith("protected ") || trimmed.startsWith("static "))) {
                methodSignature = trimmed;
                break;
            }
        }

        // 解析当前节点源码中的枚举常量
        List<String> enumValues = new ArrayList<>();
        Set<String> seenEnums = new HashSet<>();
        if (repoPath != null) {
            resolveEnumsInSource(source, repoPath, enumValues, seenEnums);
        }

        // 收集调用链上游的上下文（从入口到当前节点路径上所有节点的枚举和常量）
        List<String> chainContext = new ArrayList<>();
        if (entryMethod != null && !entryMethod.equals(fullMethod) && repoPath != null) {
            // 展开调用树，找到从入口到当前节点的路径
            CallTreeDTO tree = expandCallTree(repoId, entryMethod, 15);
            List<String> pathMethods = new ArrayList<>();
            findPathToNode(tree.root(), fullMethod, pathMethods);

            // 沿路径收集每个上游节点的枚举值
            for (String pathMethod : pathMethods) {
                if (pathMethod.equals(fullMethod)) break; // 到当前节点为止
                String upstreamSource = getMethodSource(repoId, pathMethod);
                if (upstreamSource != null) {
                    resolveEnumsInSource(upstreamSource, repoPath, chainContext, seenEnums);
                }
            }
        }

        // 解析方法参数中的实体类字段
        List<ParamClassInfo> paramClasses = new ArrayList<>();
        if (repoPath != null && methodSignature != null) {
            paramClasses = resolveParamClasses(repoPath, fullMethod);
        }

        return new MethodSourceDTO(source, methodSignature, enumValues, chainContext, paramClasses, srcResult.startLine);
    }

    /** 解析源码中的枚举常量 */
    private void resolveEnumsInSource(String source, String repoPath, List<String> results, Set<String> seen) {
        java.util.regex.Pattern enumPattern = java.util.regex.Pattern.compile("([A-Z][\\w]*)\\.([A-Z][A-Z_0-9]+)");
        java.util.regex.Matcher m = enumPattern.matcher(source);
        while (m.find()) {
            String enumClass = m.group(1);
            String enumConstant = m.group(2);
            String key = enumClass + "." + enumConstant;
            if (seen.add(key)) {
                String value = findEnumValue(repoPath, enumClass, enumConstant);
                if (value != null) {
                    results.add(key + " = " + value);
                }
            }
        }
    }

    /** 在调用树中找到从根到目标节点的路径 */
    private boolean findPathToNode(CallTreeNodeDTO node, String target, List<String> path) {
        if (node == null) return false;
        path.add(node.fullMethod());
        if (node.fullMethod().equals(target)) return true;
        if (node.children() != null) {
            for (CallTreeNodeDTO child : node.children()) {
                if (findPathToNode(child, target, path)) return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    /** 在仓库中查找枚举常量的值 */
    private String findEnumValue(String repoPath, String enumClassName, String constantName) {
        String cacheKey = repoPath + "|" + enumClassName + "." + constantName;
        String cachedVal = enumValueCache.get(cacheKey);
        if (cachedVal != null) {
            return cachedVal.isEmpty() ? null : cachedVal;
        }
        String resolved = findEnumValueUncached(repoPath, enumClassName, constantName);
        enumValueCache.put(cacheKey, resolved == null ? "" : resolved);
        return resolved;
    }

    private String findEnumValueUncached(String repoPath, String enumClassName, String constantName) {
        try (var walk = Files.walk(Path.of(repoPath), 10)) {
            Optional<Path> enumFile = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(enumClassName + ".java"))
                    .findFirst();
            if (enumFile.isEmpty()) return null;

            List<String> lines = Files.readAllLines(enumFile.get());
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith(constantName + "(") || trimmed.startsWith(constantName + " (")) {
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

    /** 解析方法参数中的实体类，提取字段列表 */
    private List<ParamClassInfo> resolveParamClasses(String repoPath, String fullMethod) {
        List<ParamClassInfo> result = new ArrayList<>();
        int parenStart = fullMethod.indexOf('(');
        int parenEnd = fullMethod.lastIndexOf(')');
        if (parenStart < 0 || parenEnd <= parenStart) return result;

        String paramStr = fullMethod.substring(parenStart + 1, parenEnd);
        if (paramStr.isBlank()) return result;

        String[] paramTypes = paramStr.split(",");
        Set<String> processed = new HashSet<>();

        for (String paramType : paramTypes) {
            String type = paramType.trim();
            if (type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jakarta.")
                    || type.startsWith("org.springframework.") || type.isEmpty() || isPrimitive(type)) continue;

            String shortName = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
            if (processed.contains(type)) continue;
            processed.add(type);

            // 用完整类名转路径直接找
            String relativePath = type.replace('.', '/') + ".java";
            List<String> fields = extractClassFieldsByPath(repoPath, relativePath, shortName);
            logger.debug("解析实体类: {} -> {} 个字段 (路径: {})", shortName, fields.size(), relativePath);
            if (!fields.isEmpty()) {
                result.add(new ParamClassInfo(type, shortName, fields));
            }
        }
        return result;
    }

    private boolean isPrimitive(String type) {
        return Set.of("int", "long", "boolean", "double", "float", "byte", "char", "short", "void").contains(type);
    }

    /** 用完整类路径找到源码文件，提取字段 */
    private List<String> extractClassFieldsByPath(String repoPath, String relativePath, String className) {
        Path sourceFile = findSourceFile(Path.of(repoPath), relativePath);
        if (sourceFile == null) {
            logger.debug("实体类文件未找到: {} (搜索路径: {})", className, relativePath);
            return List.of();
        }
        logger.debug("找到实体类文件: {}", sourceFile);
        return parseFieldsFromFile(sourceFile);
    }

    /** 从源码文件中解析字段列表 */
    private List<String> parseFieldsFromFile(Path sourceFile) {
        List<String> fields = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(sourceFile);
            String lastComment = null;
            List<String> pendingAnnotations = new ArrayList<>();

            for (String line : lines) {
                String trimmed = line.trim();

                // 注释
                if (trimmed.startsWith("//")) { lastComment = trimmed.substring(2).trim(); continue; }
                if (trimmed.startsWith("/**")) { lastComment = trimmed.replace("/**", "").replace("*/", "").replace("*", "").trim(); continue; }
                if (trimmed.startsWith("*")) {
                    String d = trimmed.substring(1).trim();
                    if (!d.isEmpty() && !d.startsWith("@")) lastComment = d;
                    continue;
                }

                // 注解 → 收集起来，等字段声明时一起输出
                if (trimmed.startsWith("@") && !trimmed.startsWith("@Override")) {
                    // 跳过类级注解
                    if (trimmed.startsWith("@Data") || trimmed.startsWith("@Getter") || trimmed.startsWith("@Setter")
                            || trimmed.startsWith("@Builder") || trimmed.startsWith("@NoArgs") || trimmed.startsWith("@AllArgs")
                            || trimmed.startsWith("@ToString") || trimmed.startsWith("@EqualsAnd")
                            || trimmed.startsWith("@Component") || trimmed.startsWith("@Service")
                            || trimmed.startsWith("@RestController") || trimmed.startsWith("@Controller")
                            || trimmed.startsWith("@Entity") || trimmed.startsWith("@Table")) {
                        continue;
                    }
                    pendingAnnotations.add(trimmed);
                    continue;
                }

                if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) continue;
                if (trimmed.equals("{") || trimmed.equals("}") || trimmed.isEmpty()) { lastComment = null; pendingAnnotations.clear(); continue; }
                if (trimmed.contains("(")) { lastComment = null; pendingAnnotations.clear(); continue; }
                if (trimmed.contains("class ") || trimmed.contains("interface ") || trimmed.contains("enum ")) { lastComment = null; pendingAnnotations.clear(); continue; }

                // 字段声明
                if (trimmed.endsWith(";") && !trimmed.startsWith("return ") && !trimmed.startsWith("super") && !trimmed.startsWith("this.")) {
                    String decl = trimmed;
                    decl = decl.replaceFirst("^(private|protected|public)\\s+", "");
                    decl = decl.replaceFirst("^(static|final|transient|volatile)\\s+", "");
                    decl = decl.replaceFirst("^(static|final|transient|volatile)\\s+", "");
                    decl = decl.replace(";", "").trim();

                    if (!decl.contains(" ")) { lastComment = null; pendingAnnotations.clear(); continue; }

                    int lastSpace = decl.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        String fieldType = decl.substring(0, lastSpace).trim();
                        String fieldName = decl.substring(lastSpace + 1).trim();
                        if (fieldName.contains("=")) fieldName = fieldName.substring(0, fieldName.indexOf('=')).trim();
                        if (fieldName.equals(fieldName.toUpperCase()) && fieldName.contains("_")) { lastComment = null; pendingAnnotations.clear(); continue; }

                        String shortType = fieldType.contains(".") ? fieldType.substring(fieldType.lastIndexOf('.') + 1) : fieldType;

                        // 从注解判断必填/约束
                        String constraints = "";
                        boolean required = false;
                        for (String anno : pendingAnnotations) {
                            if (anno.contains("NotNull") || anno.contains("NotBlank") || anno.contains("NotEmpty")) {
                                required = true;
                            }
                            // 提取约束描述
                            String shortAnno = anno.startsWith("@") ? anno : "@" + anno;
                            if (shortAnno.contains("(")) {
                                constraints += " " + shortAnno;
                            } else {
                                constraints += " " + shortAnno;
                            }
                        }

                        StringBuilder entry = new StringBuilder();
                        entry.append(required ? "* " : "  "); // * 表示必填
                        entry.append(fieldName).append(": ").append(shortType);
                        if (!constraints.isBlank()) entry.append("  ").append(constraints.trim());
                        if (lastComment != null && !lastComment.isEmpty()) entry.append("  // ").append(lastComment);

                        fields.add(entry.toString());
                    }
                    lastComment = null;
                    pendingAnnotations.clear();
                }
            }
        } catch (IOException e) { /* ignore */ }
        return fields;
    }

    private Path findSourceFile(Path repoRoot, String relativePath) {
        String cacheKey = repoRoot.toString() + "|" + relativePath;
        Path cached = sourceFileCache.get(cacheKey);
        if (cached != null) {
            return cached == SOURCE_NOT_FOUND ? null : cached;
        }
        Path found = findSourceFileUncached(repoRoot, relativePath);
        sourceFileCache.put(cacheKey, found == null ? SOURCE_NOT_FOUND : found);
        return found;
    }

    private Path findSourceFileUncached(Path repoRoot, String relativePath) {
        // 1. 标准路径: src/main/java
        Path direct = repoRoot.resolve("src/main/java").resolve(relativePath);
        if (Files.exists(direct)) return direct;

        // 2. 多模块: */src/main/java
        try (var walk = Files.walk(repoRoot, 8)) {
            Optional<Path> found = walk
                .filter(p -> p.getFileName().toString().equals("java")
                        && p.getParent() != null && "main".equals(p.getParent().getFileName().toString())
                        && p.getParent().getParent() != null && "src".equals(p.getParent().getParent().getFileName().toString())
                        && Files.isDirectory(p))
                .map(srcJavaDir -> srcJavaDir.resolve(relativePath))
                .filter(Files::exists)
                .findFirst();
            if (found.isPresent()) return found.get();
        } catch (IOException e) { /* ignore */ }

        // 3. gRPC/protobuf 生成代码: target/generated-sources/**/
        try (var walk = Files.walk(repoRoot, 10)) {
            Optional<Path> found = walk
                .filter(p -> p.getFileName().toString().equals("generated-sources")
                        && p.getParent() != null && "target".equals(p.getParent().getFileName().toString())
                        && Files.isDirectory(p))
                .flatMap(genDir -> {
                    try { return Files.walk(genDir, 3); } catch (IOException e) { return java.util.stream.Stream.empty(); }
                })
                .filter(p -> Files.isDirectory(p) && p.getFileName().toString().equals("java"))
                .map(javaDir -> javaDir.resolve(relativePath))
                .filter(Files::exists)
                .findFirst();
            if (found.isPresent()) return found.get();
        } catch (IOException e) { /* ignore */ }

        // 4. build/generated/source/proto: Gradle protobuf 插件
        try (var walk = Files.walk(repoRoot, 10)) {
            Optional<Path> found = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(relativePath.contains("/")
                        ? relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath))
                .filter(p -> p.toString().contains("generated"))
                .findFirst();
            if (found.isPresent()) return found.get();
        } catch (IOException e) { /* ignore */ }

        return null;
    }

    private String extractClassName(String fullMethod) {
        int colonIdx = fullMethod.lastIndexOf(':');
        return colonIdx > 0 ? fullMethod.substring(0, colonIdx) : fullMethod;
    }

    private String extractMethodName(String fullMethod) {
        int colonIdx = fullMethod.lastIndexOf(':');
        if (colonIdx < 0) return fullMethod;
        String methodPart = fullMethod.substring(colonIdx + 1);
        int parenIdx = methodPart.indexOf('(');
        return parenIdx > 0 ? methodPart.substring(0, parenIdx) : methodPart;
    }

    /** 简短引用：ClassSimpleName.methodName */
    private String shortRef(String fullMethod) {
        String className = extractClassName(fullMethod);
        int dotIdx = className.lastIndexOf('.');
        String simpleClass = dotIdx >= 0 ? className.substring(dotIdx + 1) : className;
        return simpleClass + "." + extractMethodName(fullMethod);
    }

    // ── 跨库追踪实现 ──────────────────────────────────────────────────────────

    private static final int CROSS_REPO_MAX_DEPTH = 15;
    private static final int CROSS_REPO_MAX_NODES = 800;

    @Override
    public CrossRepoImpactDTO getCrossRepoImpact(String fullMethod, int maxDepth) {
        int depthCap = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : Math.min(maxDepth, CROSS_REPO_MAX_DEPTH);

        // 仓库名缓存
        Map<Long, String> repoNames = new HashMap<>();
        repositoryRepo.findAll().forEach(r -> repoNames.put(r.getId(), r.getName()));

        // 歧义收集：签名 -> 告警（去重）
        Map<String, AmbiguityWarning> ambiguities = new LinkedHashMap<>();
        // 目标方法本身就可能是歧义签名
        addAmbiguityIfAny(fullMethod, repoNames, ambiguities);

        // 向上 BFS：跨所有仓库收集 caller。visited key = repoId|method
        Set<String> visited = new HashSet<>();
        // 每个 caller 记录其所在 repo、深度、是否入口点
        // repoId -> (callerFullMethod -> ImpactCaller)
        Map<Long, Map<String, ImpactCaller>> callersByRepo = new LinkedHashMap<>();
        boolean truncated = false;

        Deque<CrossRepoFrame> queue = new ArrayDeque<>();
        queue.add(new CrossRepoFrame(fullMethod, 0));
        int processed = 0;

        while (!queue.isEmpty()) {
            CrossRepoFrame frame = queue.poll();
            if (frame.depth >= depthCap) continue;
            if (processed++ > CROSS_REPO_MAX_NODES) { truncated = true; break; }

            // 全局查找：谁调用了 frame.method
            List<CallGraphEntity> edges = callGraphRepo.findByCalleeMethod(frame.method).stream()
                    .filter(c -> c.getEnabled() != null && c.getEnabled())
                    .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                    .collect(Collectors.toList());

            for (CallGraphEntity edge : edges) {
                Long repoId = edge.getRepoId();
                String caller = edge.getCallerMethod();
                String key = repoId + "|" + caller;
                if (visited.contains(key)) continue;
                visited.add(key);

                // 检测该 caller 签名是否在多个库重复定义
                addAmbiguityIfAny(caller, repoNames, ambiguities);

                boolean isEndpoint = apiEndpointRepo.findByRepoIdAndFullMethod(repoId, caller).isPresent();

                callersByRepo.computeIfAbsent(repoId, k -> new LinkedHashMap<>())
                        .put(caller, new ImpactCaller(caller, shortRef(caller),
                                edge.getCallType(), edge.getLineNumber(), frame.depth + 1, isEndpoint));

                // 入口点不再向上展开（已到顶）；非入口点继续向上找它的调用方
                if (!isEndpoint) {
                    queue.add(new CrossRepoFrame(caller, frame.depth + 1));
                }
            }
        }

        // 组装结果
        List<ImpactRepoGroup> groups = new ArrayList<>();
        int totalCallers = 0;
        int totalEndpoints = 0;
        for (Map.Entry<Long, Map<String, ImpactCaller>> e : callersByRepo.entrySet()) {
            Long repoId = e.getKey();
            List<ImpactCaller> callers = new ArrayList<>(e.getValue().values());
            totalCallers += callers.size();

            // 受影响入口点：本组中标记为 endpoint 的，补全 URL 信息
            List<ImpactEndpoint> endpoints = new ArrayList<>();
            for (ImpactCaller c : callers) {
                if (c.isEndpoint()) {
                    apiEndpointRepo.findByRepoIdAndFullMethod(repoId, c.fullMethod()).ifPresent(ep ->
                            endpoints.add(new ImpactEndpoint(ep.getEndpointType(), ep.getHttpMethod(),
                                    ep.getUrlPath(), ep.getFullMethod(), shortRef(ep.getFullMethod()))));
                }
            }
            totalEndpoints += endpoints.size();
            groups.add(new ImpactRepoGroup(repoId, repoNames.getOrDefault(repoId, "repo-" + repoId),
                    callers, endpoints));
        }

        List<AmbiguityWarning> warnings = new ArrayList<>(ambiguities.values());
        logger.info("[跨库影响] method={} 受影响仓库={} 调用方={} 入口点={} 歧义={} truncated={}",
                fullMethod, groups.size(), totalCallers, totalEndpoints, warnings.size(), truncated);
        return new CrossRepoImpactDTO(fullMethod, totalCallers, groups.size(),
                totalEndpoints, truncated, groups, warnings);
    }

    @Override
    public ImpactWithSourceDTO getImpactWithSource(String fullMethod, int maxDepth, int snippetRadius) {
        int depthCap = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : Math.min(maxDepth, CROSS_REPO_MAX_DEPTH);
        int radius = snippetRadius <= 0 ? 4 : Math.min(snippetRadius, 20);

        Map<Long, String> repoNames = new HashMap<>();
        repositoryRepo.findAll().forEach(r -> repoNames.put(r.getId(), r.getName()));

        Map<String, AmbiguityWarning> ambiguities = new LinkedHashMap<>();
        addAmbiguityIfAny(fullMethod, repoNames, ambiguities);

        // 向上 BFS，visited key = repoId|method；按 key 去重收集调用方
        Set<String> visited = new HashSet<>();
        Map<String, ImpactSourceCaller> callers = new LinkedHashMap<>();
        Set<Long> affectedRepos = new HashSet<>();
        int affectedEndpoints = 0;
        boolean truncated = false;

        Deque<CrossRepoFrame> queue = new ArrayDeque<>();
        queue.add(new CrossRepoFrame(fullMethod, 0));
        int processed = 0;

        while (!queue.isEmpty()) {
            CrossRepoFrame frame = queue.poll();
            if (frame.depth >= depthCap) continue;
            if (processed++ > CROSS_REPO_MAX_NODES) { truncated = true; break; }

            List<CallGraphEntity> edges = callGraphRepo.findByCalleeMethod(frame.method).stream()
                    .filter(c -> c.getEnabled() != null && c.getEnabled())
                    .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                    .collect(Collectors.toList());

            for (CallGraphEntity edge : edges) {
                Long repoId = edge.getRepoId();
                String caller = edge.getCallerMethod();
                String key = repoId + "|" + caller;
                if (visited.contains(key)) continue;
                visited.add(key);

                addAmbiguityIfAny(caller, repoNames, ambiguities);

                var epOpt = apiEndpointRepo.findByRepoIdAndFullMethod(repoId, caller);
                boolean isEndpoint = epOpt.isPresent();
                if (isEndpoint) affectedEndpoints++;
                affectedRepos.add(repoId);

                // 调用点源码片段：在 caller 的源码中，定位调用 frame.method 那一行附近
                String snippet = getSourceSnippet(repoId, caller, edge.getLineNumber(), radius);

                callers.put(key, new ImpactSourceCaller(
                        caller, shortRef(caller), repoId, repoNames.getOrDefault(repoId, "repo-" + repoId),
                        edge.getCallType(), edge.getLineNumber(), frame.depth + 1, isEndpoint,
                        epOpt.map(ApiEndpointEntity::getEndpointType).orElse(null),
                        epOpt.map(ApiEndpointEntity::getHttpMethod).orElse(null),
                        epOpt.map(ApiEndpointEntity::getUrlPath).orElse(null),
                        edge.getCallerReturnType(), edge.getCalleeActualReturnType(), snippet));

                // 入口点已到顶不再向上；非入口点继续追溯
                if (!isEndpoint) {
                    queue.add(new CrossRepoFrame(caller, frame.depth + 1));
                }
            }
        }

        List<ImpactSourceCaller> callerList = new ArrayList<>(callers.values());
        List<AmbiguityWarning> warnings = new ArrayList<>(ambiguities.values());
        logger.info("[影响+源码] method={} 调用方={} 仓库={} 入口点={} truncated={}",
                fullMethod, callerList.size(), affectedRepos.size(), affectedEndpoints, truncated);
        return new ImpactWithSourceDTO(fullMethod, callerList.size(), affectedRepos.size(),
                affectedEndpoints, truncated, callerList, warnings);
    }

    /**
     * 取某方法源码中指定行附近的片段（line ± radius）。用于展示调用点上下文，
     * 让 AI 看清调用方如何使用被调用方法的返回值/异常。找不到源码或行号时返回 null。
     */
    private String getSourceSnippet(Long repoId, String fullMethod, Integer line, int radius) {
        if (line == null || line <= 0) return null;
        String className = extractClassName(fullMethod);
        RepositoryEntity repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return null;
        String topLevelClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relativePath = topLevelClass.replace('.', '/') + ".java";
        Path sourcePath = findSourceFile(Path.of(repo.getLocalPath()), relativePath);
        if (sourcePath == null) return null;
        try {
            List<String> lines = Files.readAllLines(sourcePath);
            int from = Math.max(0, line - 1 - radius);
            int to = Math.min(lines.size(), line + radius);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(i + 1 == line ? ">> " : "   ").append(i + 1).append(": ").append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public CrossRepoTreeDTO getCrossRepoCallTree(String fullMethod, int maxDepth) {
        int depthCap = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : Math.min(maxDepth, CROSS_REPO_MAX_DEPTH);

        Map<Long, String> repoNames = new HashMap<>();
        repositoryRepo.findAll().forEach(r -> repoNames.put(r.getId(), r.getName()));

        // 确定根方法归属的仓库（可能在多个库定义，取第一个）
        List<ChunkEntity> defs = chunkRepo.findByFullMethod(fullMethod);
        Long rootRepoId = defs.isEmpty() ? null : defs.get(0).getRepoId();

        Map<String, AmbiguityWarning> ambiguities = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        int[] nodeCount = {0};
        boolean[] hasCycle = {false};
        boolean[] truncated = {false};

        CrossRepoNodeDTO root = buildCrossRepoNode(fullMethod, rootRepoId, null, null, false,
                repoNames, visited, expanded, 0, depthCap, nodeCount, hasCycle, truncated, ambiguities);

        List<AmbiguityWarning> warnings = new ArrayList<>(ambiguities.values());
        logger.info("[跨库调用链] method={} 节点={} hasCycle={} 歧义={} truncated={}",
                fullMethod, nodeCount[0], hasCycle[0], warnings.size(), truncated[0]);
        return new CrossRepoTreeDTO(root, nodeCount[0], depthCap, hasCycle[0], truncated[0], warnings);
    }

    private CrossRepoNodeDTO buildCrossRepoNode(String fullMethod, Long repoId,
                                                 String callType, Integer lineNumber, boolean crossesRepo,
                                                 Map<Long, String> repoNames, Set<String> visited, Set<String> expanded,
                                                 int depth, int maxDepth, int[] nodeCount,
                                                 boolean[] hasCycle, boolean[] truncated,
                                                 Map<String, AmbiguityWarning> ambiguities) {
        nodeCount[0]++;
        String sref = shortRef(fullMethod);
        String repoName = repoId != null ? repoNames.getOrDefault(repoId, "repo-" + repoId) : null;
        boolean external = repoId == null; // 无归属仓库 → 第三方/外部方法

        // 检测当前签名是否在多个库重复定义
        boolean ambiguous = addAmbiguityIfAny(fullMethod, repoNames, ambiguities);

        String visitKey = repoId + "|" + fullMethod;
        if (visited.contains(visitKey)) {
            hasCycle[0] = true;
            return new CrossRepoNodeDTO(fullMethod, sref, repoId, repoName, callType, lineNumber,
                    crossesRepo, true, false, external, ambiguous, List.of());
        }
        if (depth >= maxDepth || nodeCount[0] > CROSS_REPO_MAX_NODES) {
            if (nodeCount[0] > CROSS_REPO_MAX_NODES) truncated[0] = true;
            return new CrossRepoNodeDTO(fullMethod, sref, repoId, repoName, callType, lineNumber,
                    crossesRepo, false, true, external, ambiguous, List.of());
        }
        // 外部方法（无源码归属）不再展开
        if (external) {
            return new CrossRepoNodeDTO(fullMethod, sref, repoId, repoName, callType, lineNumber,
                    crossesRepo, false, false, true, ambiguous, List.of());
        }
        // 已在别处完整展开过（菱形汇聚点）→ 折叠为懒加载，不重复展开
        if (expanded.contains(visitKey)) {
            return new CrossRepoNodeDTO(fullMethod, sref, repoId, repoName, callType, lineNumber,
                    crossesRepo, false, true, false, ambiguous, List.of());
        }

        visited.add(visitKey);

        // 查当前方法在其所属仓库内的直接被调用方
        List<CallGraphEntity> callees = callGraphRepo.findByRepoIdAndCallerMethod(repoId, fullMethod).stream()
                .filter(c -> c.getEnabled() != null && c.getEnabled())
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .filter(c -> !isBoilerplate(c.getCalleeMethod()))
                .collect(Collectors.toList());

        List<CrossRepoNodeDTO> children = new ArrayList<>();
        for (CallGraphEntity edge : callees) {
            String callee = edge.getCalleeMethod();
            // 解析 callee 归属仓库：先看当前库是否定义，否则全局查
            Long calleeRepoId = resolveCalleeRepo(callee, repoId);
            boolean crosses = calleeRepoId != null && !calleeRepoId.equals(repoId);
            CrossRepoNodeDTO child = buildCrossRepoNode(callee, calleeRepoId,
                    edge.getCallType(), edge.getLineNumber(), crosses,
                    repoNames, visited, expanded, depth + 1, maxDepth,
                    nodeCount, hasCycle, truncated, ambiguities);
            children.add(child);
        }
        // 接口/抽象方法桥接：自身无下游时，接到当前库实现类的同签名方法继续展开
        if (callees.isEmpty()) {
            for (String impl : resolveImplementations(repoId, fullMethod)) {
                CrossRepoNodeDTO child = buildCrossRepoNode(impl, repoId,
                        "IMPL", null, false,
                        repoNames, visited, expanded, depth + 1, maxDepth,
                        nodeCount, hasCycle, truncated, ambiguities);
                children.add(child);
            }
        }

        visited.remove(visitKey);  // 离开当前路径；防重复展开由 expanded 兜底
        expanded.add(visitKey);
        return new CrossRepoNodeDTO(fullMethod, sref, repoId, repoName, callType, lineNumber,
                crossesRepo, false, false, false, ambiguous, children);
    }

    /**
     * 检测某方法签名是否在多个仓库都有定义（同名同包同参的歧义）。
     * 若是，记入 ambiguities（按签名去重）并返回 true。
     */
    private boolean addAmbiguityIfAny(String fullMethod, Map<Long, String> repoNames,
                                       Map<String, AmbiguityWarning> ambiguities) {
        if (ambiguities.containsKey(fullMethod)) return true; // 已记录
        List<ChunkEntity> defs = chunkRepo.findByFullMethod(fullMethod);
        // 按仓库去重统计定义位置
        Map<Long, ChunkEntity> byRepo = new LinkedHashMap<>();
        for (ChunkEntity d : defs) {
            byRepo.putIfAbsent(d.getRepoId(), d);
        }
        if (byRepo.size() <= 1) return false; // 唯一定义，无歧义
        List<AmbiguityLocation> locations = new ArrayList<>();
        for (ChunkEntity d : byRepo.values()) {
            locations.add(new AmbiguityLocation(d.getRepoId(),
                    repoNames.getOrDefault(d.getRepoId(), "repo-" + d.getRepoId()),
                    d.getFilePath()));
        }
        ambiguities.put(fullMethod, new AmbiguityWarning(fullMethod, locations));
        return true;
    }

    /** 解析被调用方法归属的仓库：优先当前库（有边即在当前库可见），否则全局查定义所在库 */
    private Long resolveCalleeRepo(String callee, Long currentRepoId) {
        // 当前库内若该方法本身也作为 caller 出现，说明当前库有它的实现/定义
        if (!callGraphRepo.findByRepoIdAndCallerMethod(currentRepoId, callee).isEmpty()) {
            return currentRepoId;
        }
        // 否则查 chunk 表：哪个库定义了它
        List<ChunkEntity> defs = chunkRepo.findByFullMethod(callee);
        if (defs.isEmpty()) return null; // 第三方/外部
        // 优先返回非当前库的定义（体现跨库续接）
        for (ChunkEntity d : defs) {
            if (!d.getRepoId().equals(currentRepoId)) return d.getRepoId();
        }
        return defs.get(0).getRepoId();
    }

    /** 跨库 BFS 帧 */
    private record CrossRepoFrame(String method, int depth) {}

    // ── 批量预加载缓存：全展开模式时一次性加载整个仓库数据到内存 ──────────────────

    /**
     * 仓库级数据缓存：将调用图、边界、chunk 数据一次性加载到内存。
     * 将 N×3 次逐节点 DB 查询优化为 3 次全量查询 + O(1) HashMap 查找。
     */
    private class RepoDataCache {
        final Map<String, List<CallGraphEntity>> callGraphMap;  // callerMethod -> callees
        final Map<String, List<BoundaryEntity>> boundaryMap;    // fullMethod -> boundaries
        final Map<String, ChunkEntity> chunkMap;                // fullMethod -> chunk

        RepoDataCache(Long repoId) {
            // 1. 加载全部调用边，按 callerMethod 分组
            List<CallGraphEntity> allEdges = callGraphRepo.findByRepoId(repoId);
            this.callGraphMap = new HashMap<>(allEdges.size());
            for (CallGraphEntity edge : allEdges) {
                callGraphMap.computeIfAbsent(edge.getCallerMethod(), k -> new ArrayList<>()).add(edge);
            }

            // 2. 加载全部边界，按 fullMethod 分组
            List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);
            this.boundaryMap = new HashMap<>(allBoundaries.size());
            for (BoundaryEntity b : allBoundaries) {
                boundaryMap.computeIfAbsent(b.getFullMethod(), k -> new ArrayList<>()).add(b);
            }

            // 3. 加载全部 chunk，按 fullMethod 索引
            List<ChunkEntity> allChunks = chunkRepo.findByRepoId(repoId);
            this.chunkMap = new HashMap<>(allChunks.size());
            for (ChunkEntity c : allChunks) {
                chunkMap.putIfAbsent(c.getFullMethod(), c);  // 同方法取第一个
            }
        }

        List<CallGraphEntity> getCallees(String callerMethod) {
            return callGraphMap.getOrDefault(callerMethod, List.of());
        }

        List<BoundaryEntity> getBoundaries(String fullMethod) {
            return boundaryMap.getOrDefault(fullMethod, List.of());
        }

        ChunkEntity getChunk(String fullMethod) {
            return chunkMap.get(fullMethod);
        }
    }
}
