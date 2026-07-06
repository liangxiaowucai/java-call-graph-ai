package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.dto.DebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.EnhancedDebugAnalysisResult;
import com.adrninistrator.javacg2.platform.dto.RequestChainDTO;
import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.ApiEndpointRepo;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.RequestChainAnalyzer;
import com.adrninistrator.javacg2.platform.util.ConfigFileParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 请求链分析服务 - 增强版
 * 核心功能：
 * 1. 匹配请求到后端方法
 * 2. 构建调用树（含边界信息）
 * 3. 性能分析（慢调用、N+1查询）
 * 4. 配置信息增强
 * 5. AI 智能分析
 */
@Service
public class RequestChainAnalyzerImpl implements RequestChainAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(RequestChainAnalyzerImpl.class);
    
    // 性能阈值
    private static final int SLOW_REQUEST_MS = 500;
    private static final int SLOW_DB_QUERY_MS = 100;

    private final ApiEndpointRepo apiEndpointRepo;
    private final CallGraphEngine callGraphEngine;
    private final ObjectMapper mapper;
    private final RepositoryRepo repositoryRepo;
    private final ClaudeApiClient claudeClient;
    private final ChunkRepo chunkRepo;
    private final ExternalCallFormatter externalCallFormatter;
    private final QAEngineImpl qaEngine;
    private final com.adrninistrator.javacg2.platform.service.PromptService promptService;

    // 配置缓存
    private final Map<Long, Map<String, String>> configCache = new HashMap<>();

    public RequestChainAnalyzerImpl(
            ApiEndpointRepo apiEndpointRepo,
            CallGraphEngine callGraphEngine,
            ObjectMapper mapper,
            RepositoryRepo repositoryRepo,
            ClaudeApiClient claudeClient,
            ChunkRepo chunkRepo,
            ExternalCallFormatter externalCallFormatter,
            QAEngineImpl qaEngine,
            com.adrninistrator.javacg2.platform.service.PromptService promptService) {
        this.apiEndpointRepo = apiEndpointRepo;
        this.callGraphEngine = callGraphEngine;
        this.mapper = mapper;
        this.repositoryRepo = repositoryRepo;
        this.claudeClient = claudeClient;
        this.chunkRepo = chunkRepo;
        this.externalCallFormatter = externalCallFormatter;
        this.qaEngine = qaEngine;
        this.promptService = promptService;
    }

    @Override
    public DebugAnalysisResult analyze(RequestChainDTO chain) {
        logger.info("[请求链分析] sessionId={}", chain.getSessionId());

        DebugAnalysisResult result = new DebugAnalysisResult();
        
        if (chain.getRequestChain() == null || chain.getRequestChain().isEmpty()) {
            result.setSummary("没有捕获到请求");
            return result;
        }

        try {
            // 1. 匹配并构建调用树
            List<DebugAnalysisResult.ApiCall> apiCalls = buildApiCalls(chain);
            result.setApiCalls(apiCalls);

            // 2. 数据流分析
            List<DebugAnalysisResult.DataFlowIssue> issues = new ArrayList<>();
            result.setDataFlowIssues(issues);

            // 3. 仓库推荐
            List<DebugAnalysisResult.RepoRecommendation> recommendations = aggregateRepos(apiCalls);
            result.setRecommendedRepos(recommendations);

            // 4. 生成报告
            result.setSummary(generateSummary(chain, apiCalls));
            result.setReport(generateReport(chain, apiCalls));
            
            // 5. AI 分析（可选）
            if (claudeClient.isConfigured()) {
                try {
                    String aiReport = generateAIAnalysis(chain, apiCalls);
                    result.setReport(result.getReport() + "\n\n" + aiReport);
                } catch (Exception e) {
                    logger.warn("[AI分析] 失败", e);
                }
            }

        } catch (Exception e) {
            logger.error("[分析失败]", e);
            result.setSummary("分析失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    public EnhancedDebugAnalysisResult analyzeWithCallGraph(RequestChainDTO chain) {
        EnhancedDebugAnalysisResult result = new EnhancedDebugAnalysisResult();
        
        DebugAnalysisResult base = analyze(chain);
        result.setSummary(base.getSummary());
        result.setApiCalls(base.getApiCalls());
        result.setDataFlowIssues(base.getDataFlowIssues());
        result.setReport(base.getReport());
        result.setRecommendedRepos(base.getRecommendedRepos());
        
        result.setRequestSequence(buildRequestSequence(chain, base.getApiCalls()));
        result.setCrossRequestChains(buildCrossRequestChains(base.getApiCalls()));
        
        return result;
    }

    // ==================== 核心方法 ====================
    
    /**
     * 构建 API 调用列表
     */
    private List<DebugAnalysisResult.ApiCall> buildApiCalls(RequestChainDTO chain) {
        List<DebugAnalysisResult.ApiCall> calls = new ArrayList<>();
        
        for (RequestChainDTO.RequestInfo req : chain.getRequestChain()) {
            DebugAnalysisResult.ApiCall call = new DebugAnalysisResult.ApiCall();
            call.setUrl(req.getUrl());
            call.setRequestMethod(req.getMethod());
            call.setRequestBody(req.getRequestBody());
            call.setResponseBody(req.getResponseBody());
            call.setStatus(req.getResponseStatus());
            
            // 匹配端点
            ApiEndpointEntity endpoint = matchEndpoint(req.getUrl(), req.getMethod());
            if (endpoint != null) {
                call.setMethod(endpoint.getFullMethod());
                
                // 获取调用树
                try {
                    CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(
                        endpoint.getRepoId(),
                        endpoint.getFullMethod(),
                        Integer.MAX_VALUE,
                        true
                    );
                    call.setCallTree(tree);
                } catch (Exception e) {
                    logger.debug("[调用树] 获取失败", e);
                }
                
                // 仓库推荐
                call.setRecommendedRepo(buildRepoRecommendation(endpoint));
            }
            
            calls.add(call);
        }
        
        return calls;
    }
    
    /**
     * 匹配 API 端点（考虑 urlPathIdentifier 前缀）
     */
    private ApiEndpointEntity matchEndpoint(String url, String method) {
        if (url == null) return null;
        
        String path = url.split("\\?")[0];
        
        List<ApiEndpointEntity> all = apiEndpointRepo.findAll();
        for (ApiEndpointEntity e : all) {
            // 匹配 HTTP 控制器端点（CONTROLLER 类型）
            if (!"CONTROLLER".equals(e.getEndpointType())) {
                continue;
            }
            
            // 获取仓库的 URL 路径标识符
            String prefix = "";
            try {
                prefix = repositoryRepo.findById(e.getRepoId())
                    .map(RepositoryEntity::getUrlPathIdentifier)
                    .orElse("");
            } catch (Exception ex) {
                logger.debug("获取仓库URL标识符失败", ex);
            }
            
            // 构建完整的匹配路径
            String fullPattern = e.getUrlPath();
            if (prefix != null && !prefix.isEmpty()) {
                // 处理多个前缀的情况（用逗号分隔）
                String[] prefixes = prefix.split(",");
                for (String p : prefixes) {
                    p = p.trim();
                    if (!p.isEmpty()) {
                        String testPath = p + e.getUrlPath();
                        if (pathMatches(path, testPath) && 
                            (method == null || method.equalsIgnoreCase(e.getHttpMethod()))) {
                            return e;
                        }
                    }
                }
            }
            
            // 兜底：不带前缀直接匹配
            if (pathMatches(path, fullPattern) &&
                (method == null || method.equalsIgnoreCase(e.getHttpMethod()))) {
                return e;
            }
        }
        
        return null;
    }
    
    /**
     * 路径匹配（支持 {id} 参数）
     */
    private boolean pathMatches(String requestPath, String templatePath) {
        if (templatePath == null) return false;
        
        String[] reqSegs = requestPath.split("/");
        String[] tplSegs = templatePath.split("/");
        
        if (reqSegs.length != tplSegs.length) return false;
        
        for (int i = 0; i < reqSegs.length; i++) {
            if (!tplSegs[i].startsWith("{") && !tplSegs[i].equals(reqSegs[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 构建仓库推荐
     */
    private DebugAnalysisResult.RepoRecommendation buildRepoRecommendation(ApiEndpointEntity endpoint) {
        DebugAnalysisResult.RepoRecommendation rec = new DebugAnalysisResult.RepoRecommendation();
        rec.setRepoId(endpoint.getRepoId());
        
        try {
            String name = repositoryRepo.findById(endpoint.getRepoId())
                .map(RepositoryEntity::getName)
                .orElse("未知");
            rec.setRepoName(name);
        } catch (Exception e) {
            rec.setRepoName("未知");
        }
        
        rec.setConfidence(1.0);
        rec.setReason("精确匹配");
        rec.setMatchedUrls(Collections.singletonList(endpoint.getUrlPath()));
        
        return rec;
    }
    
    /**
     * 聚合仓库推荐
     */
    private List<DebugAnalysisResult.RepoRecommendation> aggregateRepos(List<DebugAnalysisResult.ApiCall> calls) {
        Map<Long, DebugAnalysisResult.RepoRecommendation> map = new LinkedHashMap<>();
        
        for (DebugAnalysisResult.ApiCall call : calls) {
            if (call.getRecommendedRepo() != null) {
                Long id = call.getRecommendedRepo().getRepoId();
                map.putIfAbsent(id, call.getRecommendedRepo());
            }
        }
        
        return new ArrayList<>(map.values());
    }
    
    // ==================== 报告生成 ====================
    
    /**
     * 生成摘要
     */
    private String generateSummary(RequestChainDTO chain, List<DebugAnalysisResult.ApiCall> calls) {
        int total = chain.getRequestChain().size();
        int matched = (int) calls.stream().filter(c -> c.getMethod() != null).count();
        int failed = (int) chain.getRequestChain().stream()
            .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
            .count();
        
        return String.format("总请求: %d | 已匹配: %d | 失败: %d", total, matched, failed);
    }
    
    /**
     * 生成详细报告（增强版）
     */
    private String generateReport(RequestChainDTO chain, List<DebugAnalysisResult.ApiCall> calls) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 请求链分析报告\n\n");
        
        // 关键指标摘要（Task #4）
        sb.append(generatePerformanceOverview(chain, calls));
        sb.append("\n\n");
        
        sb.append("## 请求详情\n\n");
        
        for (int i = 0; i < calls.size(); i++) {
            DebugAnalysisResult.ApiCall call = calls.get(i);
            RequestChainDTO.RequestInfo req = i < chain.getRequestChain().size() ? 
                chain.getRequestChain().get(i) : null;
            
            sb.append(String.format("### %d. %s %s\n\n", i+1, call.getRequestMethod(), call.getUrl()));
            
            // 性能标注
            if (req != null && req.getDuration() != null) {
                sb.append(String.format("⏱️ **耗时**: %dms", req.getDuration()));
                if (req.getDuration() > SLOW_REQUEST_MS) {
                    sb.append(" 🐢 **慢请求**");
                }
                sb.append("\n");
            }
            
            if (call.getMethod() != null) {
                sb.append(String.format("📍 **方法**: `%s`\n", call.getMethod()));
                
                // 外部调用详情（Task #3）
                if (call.getCallTree() != null) {
                    Long callRepoId = call.getRecommendedRepo() != null ? call.getRecommendedRepo().getRepoId() : null;
                    sb.append(analyzeBoundaries(call.getCallTree(), callRepoId));
                }
            } else {
                sb.append("⚠️ **未匹配到后端方法**\n");
            }
            
            sb.append(String.format("📊 **状态**: %d\n\n", call.getStatus()));
        }
        
        return sb.toString();
    }
    
    /**
     * Task #4: 生成性能总览（一键看懂）
     */
    private String generatePerformanceOverview(RequestChainDTO chain, List<DebugAnalysisResult.ApiCall> calls) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 📊 性能总览\n\n");
        
        // 计算关键指标
        long totalDuration = chain.getRequestChain().stream()
            .mapToLong(r -> r.getDuration() != null ? r.getDuration() : 0)
            .sum();
        
        long slowRequests = chain.getRequestChain().stream()
            .filter(r -> r.getDuration() != null && r.getDuration() > SLOW_REQUEST_MS)
            .count();
        
        long failedRequests = chain.getRequestChain().stream()
            .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
            .count();
        
        // 统计所有外部调用
        int totalDbCalls = 0;
        int totalHttpCalls = 0;
        int totalCacheCalls = 0;
        
        for (DebugAnalysisResult.ApiCall call : calls) {
            if (call.getCallTree() != null) {
                Map<String, Integer> counts = countBoundaries(call.getCallTree());
                totalDbCalls += counts.getOrDefault("DB", 0);
                totalHttpCalls += counts.getOrDefault("HTTP", 0);
                totalCacheCalls += counts.getOrDefault("CACHE", 0) + counts.getOrDefault("REDIS", 0);
            }
        }
        
        // 性能评分已移除（本次实测耗时不代表架构风险，架构隐患见 AI 分析）
        sb.append(String.format("| 指标 | 数值 | 状态 |\n"));
        sb.append("|------|------|------|\n");
        sb.append(String.format("| 总耗时 | %dms | %s |\n", 
            totalDuration, 
            totalDuration > 2000 ? "⚠️" : "✅"));
        sb.append(String.format("| 慢请求 | %d/%d | %s |\n", 
            slowRequests, 
            chain.getRequestChain().size(),
            slowRequests > 0 ? "⚠️" : "✅"));
        sb.append(String.format("| 失败请求 | %d/%d | %s |\n", 
            failedRequests, 
            chain.getRequestChain().size(),
            failedRequests > 0 ? "❌" : "✅"));
        sb.append(String.format("| 数据库调用 | %d 次 | %s |\n", 
            totalDbCalls,
            totalDbCalls > 20 ? "⚠️" : "✅"));
        sb.append(String.format("| HTTP调用 | %d 次 | %s |\n", 
            totalHttpCalls,
            totalHttpCalls > 10 ? "⚠️" : "✅"));
        sb.append(String.format("| 缓存调用 | %d 次 | - |\n", totalCacheCalls));
        
        return sb.toString();
    }
    
    /**
     * Task #3: 分析边界调用（SQL展示、N+1检测）
     * Task #6: 添加常量和异常展示
     */
    private String analyzeBoundaries(Object callTree, Long repoId) {
        StringBuilder sb = new StringBuilder();
        
        if (!(callTree instanceof CallGraphEngine.CallTreeDTO)) {
            return "";
        }
        
        CallGraphEngine.CallTreeDTO tree = (CallGraphEngine.CallTreeDTO) callTree;
        List<BoundaryDetail> boundaries = extractBoundaryDetails(tree.root(), new ArrayList<>(), new HashSet<>());
        
        if (boundaries.isEmpty()) {
            return "";
        }
        
        // 按类型分组
        Map<String, List<BoundaryDetail>> grouped = new LinkedHashMap<>();
        for (BoundaryDetail b : boundaries) {
            grouped.computeIfAbsent(b.type, k -> new ArrayList<>()).add(b);
        }
        
        sb.append("\n#### 🔗 外部调用分析\n\n");
        
        // DB 调用（重点分析）
        if (grouped.containsKey("DB")) {
            List<BoundaryDetail> dbCalls = grouped.get("DB");
            sb.append(String.format("**🗄️ 数据库调用** (%d次)\n\n", dbCalls.size()));
            
            // N+1查询检测
            if (dbCalls.size() > 5) {
                sb.append("⚠️ **疑似N+1查询问题**：检测到").append(dbCalls.size()).append("次数据库调用\n\n");
            }
            
            // 展示前3个SQL
            for (int i = 0; i < Math.min(dbCalls.size(), 3); i++) {
                BoundaryDetail db = dbCalls.get(i);
                sb.append(String.format("%d. ", i+1));
                if (db.context != null && db.context.contains("SELECT")) {
                    // 格式化SQL
                    String sql = formatSql(db.context);
                    sb.append("```sql\n").append(sql).append("\n```\n");
                    
                    // SQL分析
                    sb.append(analyzeSql(sql)).append("\n");
                } else {
                    sb.append("`").append(db.context != null ? db.context : "SQL查询").append("`\n\n");
                }
            }
            
            if (dbCalls.size() > 3) {
                sb.append(String.format("\n*...还有 %d 个数据库调用*\n\n", dbCalls.size() - 3));
            }
        }
        
        // HTTP 调用
        if (grouped.containsKey("HTTP")) {
            List<BoundaryDetail> httpCalls = grouped.get("HTTP");
            sb.append(String.format("**🌐 HTTP调用** (%d次)\n\n", httpCalls.size()));

            // 走统一的 ExternalCallFormatter 装配「【系统名】HTTP调用 `完整URL` — 用途」，与 AI 文档/调用链地图格式一致。
            // 按 fullMethod 取 chunk → formatter 拼 base+path 完整 URL；无 chunk 数据时回退到 boundary 原始 URL。
            Set<String> shownHttp = new LinkedHashSet<>();
            for (BoundaryDetail http : httpCalls) {
                List<String> rendered = new ArrayList<>();
                if (repoId != null && http.location != null) {
                    var chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, http.location).orElse(null);
                    if (chunk != null) {
                        for (var ec : externalCallFormatter.extract(chunk)) rendered.add(ec.render());
                    }
                }
                if (rendered.isEmpty()) {
                    // 回退：boundary context 里的 base URL（拼不出完整 path 时至少展示已知信息）
                    String urlMatch = http.context != null && http.context.contains("📌 URL:")
                        ? http.context.substring(http.context.indexOf("📌 URL:") + 7).trim()
                        : http.context;
                    rendered.add("HTTP调用 `" + (urlMatch != null ? urlMatch : "HTTP请求") + "`");
                }
                for (String line : rendered) {
                    if (shownHttp.add(line)) sb.append("- ").append(line).append("\n");
                }
            }
            sb.append("\n");
        }
        
        // 缓存调用
        if (grouped.containsKey("CACHE") || grouped.containsKey("REDIS")) {
            int cacheCount = grouped.getOrDefault("CACHE", Collections.emptyList()).size() +
                           grouped.getOrDefault("REDIS", Collections.emptyList()).size();
            sb.append(String.format("**⚡ 缓存调用** (%d次)\n\n", cacheCount));
        }
        
        // 提取并展示常量和异常信息（带源码引用）
        ConstantsAndExceptions constsAndExceps = extractConstantsAndExceptions(tree.root(), new HashSet<>());
        
        if (!constsAndExceps.constants.isEmpty() || !constsAndExceps.exceptions.isEmpty()) {
            sb.append("\n#### 📝 常量与异常\n\n");
            
            // 展示关键常量（值 + 源码出处）
            if (!constsAndExceps.constants.isEmpty()) {
                sb.append("**🔢 使用的常量值：**\n\n");
                int shown = 0;
                for (String c : constsAndExceps.constants) {
                    if (shown++ >= 30) break;
                    sb.append("- ").append(c).append("\n");
                }
                sb.append("\n");
            }
            
            // 展示异常类型（具体 type + 源码）
            if (!constsAndExceps.exceptions.isEmpty()) {
                sb.append("**⚠️ 抛出/捕获的异常：**\n\n");
                constsAndExceps.exceptions.forEach(exc -> sb.append("- ").append(exc).append("\n"));
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 提取边界详情
     */
    private List<BoundaryDetail> extractBoundaryDetails(CallGraphEngine.CallTreeNodeDTO node,
                                                       List<BoundaryDetail> result,
                                                       Set<String> visited) {
        if (node == null || node.fullMethod() == null) return result;
        if (!visited.add(node.fullMethod())) return result;
        
        if (node.boundaries() != null) {
            for (CallGraphEngine.BoundaryDTO b : node.boundaries()) {
                result.add(new BoundaryDetail(
                    b.boundaryType(),
                    b.context(),
                    node.fullMethod(),
                    b.lineNumber()
                ));
            }
        }
        
        if (node.children() != null && visited.size() < 100) {
            for (CallGraphEngine.CallTreeNodeDTO child : node.children()) {
                extractBoundaryDetails(child, result, visited);
            }
        }
        
        return result;
    }
    
    /**
     * Task #3: 格式化SQL
     */
    private String formatSql(String sql) {
        if (sql == null || sql.isBlank()) return "";
        
        // 简单格式化：添加换行
        return sql.trim()
            .replaceAll("(?i)\\s+(SELECT|FROM|WHERE|AND|OR|ORDER BY|GROUP BY|LIMIT)\\s+", "\n$1 ")
            .replaceAll("(?i)\\s+(INNER|LEFT|RIGHT|FULL)\\s+JOIN\\s+", "\n$1 JOIN ")
            .trim();
    }
    
    /**
     * Task #3: 分析SQL（识别慢查询类型）
     */
    private String analyzeSql(String sql) {
        if (sql == null) return "";
        
        List<String> issues = new ArrayList<>();
        
        String upper = sql.toUpperCase();
        
        // SELECT *
        if (upper.contains("SELECT *")) {
            issues.add("⚠️ 使用了 `SELECT *`，建议指定具体字段");
        }
        
        // 缺少WHERE
        if (upper.contains("SELECT") && !upper.contains("WHERE") && !upper.contains("LIMIT")) {
            issues.add("⚠️ 可能是全表扫描（无WHERE条件）");
        }
        
        // 多个JOIN
        int joinCount = countOccurrences(upper, "JOIN");
        if (joinCount >= 3) {
            issues.add(String.format("⚠️ 包含%d个JOIN，可能影响性能", joinCount));
        }
        
        // 子查询
        if (countOccurrences(upper, "SELECT") > 1) {
            issues.add("💡 包含子查询，建议考虑JOIN优化");
        }
        
        if (issues.isEmpty()) {
            return "*SQL结构正常*\n";
        }
        
        return String.join("\n", issues) + "\n";
    }
    
    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
    
    /**
     * 边界详情
     */
    private static class BoundaryDetail {
        String type;
        String context;
        String location;
        Integer lineNumber;
        
        BoundaryDetail(String type, String context, String location, Integer lineNumber) {
            this.type = type;
            this.context = context;
            this.location = location;
            this.lineNumber = lineNumber;
        }
    }
    
    /**
     * 统计边界调用次数
     */
    private Map<String, Integer> countBoundaries(Object callTree) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        
        if (callTree instanceof CallGraphEngine.CallTreeDTO) {
            countBoundariesInNode(((CallGraphEngine.CallTreeDTO) callTree).root(), counts, new HashSet<>());
        }
        
        return counts;
    }
    
    private void countBoundariesInNode(CallGraphEngine.CallTreeNodeDTO node,
                                      Map<String, Integer> counts,
                                      Set<String> visited) {
        if (node == null || node.fullMethod() == null) return;
        if (!visited.add(node.fullMethod())) return;
        
        if (node.boundaries() != null) {
            for (CallGraphEngine.BoundaryDTO b : node.boundaries()) {
                counts.merge(b.boundaryType(), 1, Integer::sum);
            }
        }
        
        if (node.children() != null && visited.size() < 100) {
            for (CallGraphEngine.CallTreeNodeDTO child : node.children()) {
                countBoundariesInNode(child, counts, visited);
            }
        }
    }
    
    /**
     * 边界类型 emoji
     */
    private String getBoundaryEmoji(String type) {
        return switch (type) {
            case "HTTP" -> "🌐";
            case "DB" -> "🗄️";
            case "RPC", "GRPC" -> "📡";
            case "CACHE", "REDIS" -> "⚡";
            case "MQ" -> "📬";
            default -> "🔌";
        };
    }
    
    /**
     * 提取常量和异常信息 —— 解析节点的 JSON 结构化数据（带源码引用）
     * node.constants(): [{value,line,code,file}]   node.exceptions(): [{kind,type,line,code,file}]
     */
    private ConstantsAndExceptions extractConstantsAndExceptions(CallGraphEngine.CallTreeNodeDTO node, Set<String> visited) {
        ConstantsAndExceptions result = new ConstantsAndExceptions();

        if (node == null || node.fullMethod() == null) return result;
        if (!visited.add(node.fullMethod())) return result;

        // 常量：只有静态常量/枚举引用（有名称的），字面量值不再展示（在入参/错误码区）
        if (node.constants() != null && !node.constants().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(node.constants());
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode c : arr) {
                        String value = c.path("value").asText("");
                        if (value.isEmpty()) continue;
                        // 跳过接口 path 字面量（以 / 开头）：它们归外部调用区，由 ExternalCallFormatter 统一展示，不在常量区重复
                        if (value.charAt(0) == '/') continue;
                        StringBuilder sb = new StringBuilder("`").append(value);
                        String resolved = c.path("resolvedValue").asText(null);
                        if (resolved != null && !resolved.isBlank()) {
                            sb.append(" = ").append(resolved);
                        }
                        sb.append("`");
                        String file = c.path("file").asText(null);
                        int line = c.path("line").asInt(0);
                        if (file != null && line > 0) {
                            sb.append(" @ ").append(file).append(":").append(line);
                        }
                        result.constants.add(sb.toString());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 异常区现改为业务错误码+消息：node.exceptions() = [{code,msg,line,codeText,file}]
        if (node.exceptions() != null && !node.exceptions().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(node.exceptions());
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode e : arr) {
                        String code = e.path("code").asText("");
                        String msg = e.path("msg").asText("");
                        if (code.isEmpty() && msg.isEmpty()) continue;
                        StringBuilder sb = new StringBuilder("`");
                        if (!code.isEmpty()) sb.append("code=").append(code);
                        if (!msg.isEmpty()) sb.append(code.isEmpty() ? "" : ", ").append("msg=\"").append(msg).append("\"");
                        sb.append("`");
                        String file = e.path("file").asText(null);
                        int line = e.path("line").asInt(0);
                        if (file != null && line > 0) sb.append(" @ ").append(file).append(":").append(line);
                        result.exceptions.add(sb.toString());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 递归处理子节点
        if (node.children() != null && visited.size() < 100) {
            for (CallGraphEngine.CallTreeNodeDTO child : node.children()) {
                ConstantsAndExceptions childResult = extractConstantsAndExceptions(child, visited);
                result.constants.addAll(childResult.constants);
                result.exceptions.addAll(childResult.exceptions);
            }
        }

        return result;
    }

    /**
     * 常量和异常容器
     */
    private static class ConstantsAndExceptions {
        Set<String> constants = new LinkedHashSet<>();   // 带源码出处的常量值，保持顺序去重
        Set<String> exceptions = new LinkedHashSet<>();  // 带源码的异常，保持顺序去重
    }
    
    // ==================== AI 分析 ====================
    
    /**
     * AI 智能分析（非流式）——复用 tool-loop 引擎，按需拉取源码，不再一次性拼接巨型上下文
     */
    private String generateAIAnalysis(RequestChainDTO chain, List<DebugAnalysisResult.ApiCall> calls) {
        try {
            List<QAEngineImpl.ScoredEndpoint> scored = resolveScoredFromCalls(calls);
            if (scored.isEmpty()) {
                return "## 🤖 AI 智能分析\n\n*未匹配到后端方法，跳过分析*";
            }
            List<QAEngineImpl.MatchedEndpoint> matched = new ArrayList<>();
            for (QAEngineImpl.ScoredEndpoint se : scored) {
                matched.add(qaEngine.toMatchedEndpointPublic(se));
            }
            QAEngineImpl.SmartQAResponse resp = qaEngine.generateAnswerWithLoop(
                    scored, buildAiQuestion(chain, calls), List.of(), matched, null);
            return "## 🤖 AI 智能分析\n\n" + resp.answer();
        } catch (Exception e) {
            logger.error("[AI分析] 失败", e);
            return "## 🤖 AI 智能分析\n\n*暂不可用*";
        }
    }

    /** 从匹配到后端方法的 ApiCall 收集入口，转成 tool-loop 引擎所需的 ScoredEndpoint 列表 */
    private List<QAEngineImpl.ScoredEndpoint> resolveScoredFromCalls(List<DebugAnalysisResult.ApiCall> calls) {
        List<QAEngineImpl.ScoredEndpoint> scored = new ArrayList<>();
        for (DebugAnalysisResult.ApiCall call : calls) {
            if (call.getMethod() != null && call.getRecommendedRepo() != null) {
                scored.addAll(qaEngine.resolveMethodsToScored(
                        call.getRecommendedRepo().getRepoId(), List.of(call.getMethod())));
            }
        }
        return scored;
    }

    /**
     * 构建 AI 分析的「问题」——轻量的请求链摘要 + 分析诉求。
     * 只含接口/耗时/状态/请求响应体摘要/入口方法，源码与调用链细节由 AI 通过工具按需拉取，
     * 从而避免一次性发送巨型 prompt 导致的超时。
     */
    private String buildAiQuestion(RequestChainDTO chain, List<DebugAnalysisResult.ApiCall> calls) {
        StringBuilder q = new StringBuilder();
        q.append(promptService.get("debug.analysis")).append("\n")
         .append("## 请求链摘要\n");
        for (int i = 0; i < Math.min(calls.size(), 2); i++) {
            DebugAnalysisResult.ApiCall call = calls.get(i);
            RequestChainDTO.RequestInfo req = i < chain.getRequestChain().size() ? chain.getRequestChain().get(i) : null;
            q.append("\n### 请求 ").append(i + 1).append("\n");
            q.append("- 接口: ").append(call.getRequestMethod() != null ? call.getRequestMethod() : "")
             .append(" ").append(call.getUrl()).append("\n");
            if (req != null && req.getDuration() != null) q.append("- 总耗时: ").append(req.getDuration()).append("ms\n");
            q.append("- HTTP 状态: ").append(call.getStatus()).append("\n");
            if (call.getRequestBody() != null) q.append("- 请求体: ").append(truncate(call.getRequestBody(), 600)).append("\n");
            if (call.getResponseBody() != null) q.append("- 响应体: ").append(truncate(call.getResponseBody(), 400)).append("\n");
            if (call.getMethod() != null) {
                q.append("- 后端入口方法: ").append(call.getMethod()).append("\n");
            } else {
                q.append("（未匹配到后端方法）\n");
            }
        }
        return q.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }

    // ==================== 流式分析（SSE） ====================

    @Override
    public void analyzeStreaming(RequestChainDTO chain, StreamListener listener) {
        try {
            if (chain.getRequestChain() == null || chain.getRequestChain().isEmpty()) {
                listener.progress("error", "没有捕获到请求");
                return;
            }
            int total = chain.getRequestChain().size();
            listener.progress("parse", "解析请求链，共 " + total + " 个请求");

            // 逐个请求匹配端点 + 构建调用树（带进度）
            List<DebugAnalysisResult.ApiCall> calls = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                RequestChainDTO.RequestInfo req = chain.getRequestChain().get(i);
                DebugAnalysisResult.ApiCall call = new DebugAnalysisResult.ApiCall();
                call.setUrl(req.getUrl());
                call.setRequestMethod(req.getMethod());
                call.setRequestBody(req.getRequestBody());
                call.setResponseBody(req.getResponseBody());
                call.setStatus(req.getResponseStatus());
                call.setRequestHeaders(req.getRequestHeaders());

                listener.progress("match", String.format("[%d/%d] 匹配端点: %s %s", i + 1, total,
                        req.getMethod() != null ? req.getMethod() : "", req.getUrl()));
                ApiEndpointEntity endpoint = matchEndpoint(req.getUrl(), req.getMethod());
                if (endpoint != null) {
                    call.setMethod(endpoint.getFullMethod());
                    listener.progress("match", "✓ 命中后端方法: " + endpoint.getFullMethod());
                    try {
                        listener.progress("tree", "构建调用树（深度优先，全展开异步/lambda调用）...");
                        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(
                                endpoint.getRepoId(), endpoint.getFullMethod(), Integer.MAX_VALUE, true);
                        call.setCallTree(tree);

                        Map<String, Integer> bcount = countBoundaries(tree);
                        ConstantsAndExceptions ce = extractConstantsAndExceptions(tree.root(), new HashSet<>());
                        int httpN = bcount.getOrDefault("HTTP", 0);
                        int dbN = bcount.getOrDefault("DB", 0);
                        listener.progress("data", String.format(
                                "提取证据: 常量 %d 项 / 异常 %d 项 / 外部HTTP %d / DB %d",
                                ce.constants.size(), ce.exceptions.size(), httpN, dbN));
                        // 把解析出的真实外部 URL 实时回显
                        emitHttpUrls(tree, listener);
                    } catch (Exception e) {
                        listener.progress("tree", "调用树构建失败: " + e.getMessage());
                    }
                    call.setRecommendedRepo(buildRepoRecommendation(endpoint));
                } else {
                    listener.progress("match", "✗ 未匹配到后端方法（仓库未分析或前端直连资源）");
                }
                calls.add(call);
            }

            // 构建结果（不含 AI）
            listener.progress("report", "生成分析报告...");
            EnhancedDebugAnalysisResult result = new EnhancedDebugAnalysisResult();
            result.setApiCalls(calls);
            result.setDataFlowIssues(new ArrayList<>());
            result.setRecommendedRepos(aggregateRepos(calls));
            result.setSummary(generateSummary(chain, calls));
            result.setReport(generateReport(chain, calls));
            result.setRequestSequence(buildRequestSequence(chain, calls));
            result.setCrossRequestChains(buildCrossRequestChains(calls));
            listener.result(result);

            // AI 流式分析
            if (claudeClient.isConfigured()) {
                listener.progress("ai", "正在调用 AI 进行性能与风险分析...");
                try {
                    // 收集匹配到后端方法的入口 (repoId + fullMethod)，交给 tool-loop 引擎按需拉源码，
                    // 避免一次性拼接巨型上下文（会导致 prompt 过大、AI 调用超时）
                    List<QAEngineImpl.ScoredEndpoint> scored = new ArrayList<>();
                    for (DebugAnalysisResult.ApiCall call : calls) {
                        if (call.getMethod() != null && call.getRecommendedRepo() != null) {
                            scored.addAll(qaEngine.resolveMethodsToScored(
                                    call.getRecommendedRepo().getRepoId(), List.of(call.getMethod())));
                        }
                    }
                    if (scored.isEmpty()) {
                        listener.progress("ai", "未匹配到后端方法，跳过 AI 分析");
                        listener.aiDone("");
                    } else {
                        List<QAEngineImpl.MatchedEndpoint> matched = new ArrayList<>();
                        for (QAEngineImpl.ScoredEndpoint se : scored) {
                            matched.add(qaEngine.toMatchedEndpointPublic(se));
                        }
                        String question = buildAiQuestion(chain, calls);
                        QAEngineImpl.SmartQAResponse resp = qaEngine.generateAnswerWithLoop(
                                scored, question, List.of(), matched,
                                // 工具调用步骤 → 进度事件（前端已渲染 progress）
                                step -> listener.progress("ai-step",
                                        step.label() + (step.detail() != null && !step.detail().isBlank()
                                                ? " — " + step.detail() : "")),
                                null,
                                // 最终答案逐 token 流式输出
                                listener::aiToken);
                        listener.aiDone(resp.answer());
                    }
                } catch (Exception e) {
                    logger.warn("[流式AI] 失败", e);
                    listener.progress("ai", "AI 分析不可用: " + e.getMessage());
                    listener.aiDone("");
                }
            } else {
                listener.progress("ai", "未配置 AI，跳过智能分析");
                listener.aiDone("");
            }
        } catch (Exception e) {
            logger.error("[流式分析] 失败", e);
            listener.progress("error", "分析失败: " + e.getMessage());
        }
    }

    /** 遍历调用树，把解析出的真实外部 URL 通过进度实时回显 */
    private void emitHttpUrls(CallGraphEngine.CallTreeDTO tree, StreamListener listener) {
        if (tree == null || tree.root() == null) return;
        Set<String> seen = new HashSet<>();
        emitHttpUrlsNode(tree.root(), listener, seen, new HashSet<>());
    }

    private void emitHttpUrlsNode(CallGraphEngine.CallTreeNodeDTO node, StreamListener listener,
                                  Set<String> seenUrls, Set<String> visited) {
        if (node == null || node.fullMethod() == null || !visited.add(node.fullMethod())) return;
        if (node.boundaries() != null) {
            for (CallGraphEngine.BoundaryDTO b : node.boundaries()) {
                if ("HTTP".equals(b.boundaryType()) && b.context() != null) {
                    String url = b.context().replace("📌 URL:", "").trim();
                    if (seenUrls.add(url)) {
                        listener.progress("url", "解析到外部调用 URL: " + url);
                    }
                }
            }
        }
        if (node.children() != null) {
            for (CallGraphEngine.CallTreeNodeDTO c : node.children()) emitHttpUrlsNode(c, listener, seenUrls, visited);
        }
    }

    
    private List<EnhancedDebugAnalysisResult.RequestSequenceItem> buildRequestSequence(
            RequestChainDTO chain,
            List<DebugAnalysisResult.ApiCall> calls) {
        
        List<EnhancedDebugAnalysisResult.RequestSequenceItem> seq = new ArrayList<>();
        
        for (int i = 0; i < chain.getRequestChain().size(); i++) {
            RequestChainDTO.RequestInfo req = chain.getRequestChain().get(i);
            EnhancedDebugAnalysisResult.RequestSequenceItem item =
                new EnhancedDebugAnalysisResult.RequestSequenceItem();
            
            item.setSeq(i + 1);
            item.setTimestamp(req.getTimestamp());
            item.setMethod(req.getMethod());
            item.setUrl(req.getUrl());
            item.setFullUrl(req.getFullUrl());
            item.setResponseStatus(req.getResponseStatus());
            item.setDuration(req.getDuration());
            item.setSuccess(req.getSuccess());
            
            if (i < calls.size() && calls.get(i).getMethod() != null) {
                item.setBackendMethod(calls.get(i).getMethod());
                if (calls.get(i).getRecommendedRepo() != null) {
                    item.setRepoId(calls.get(i).getRecommendedRepo().getRepoId());
                }
                item.setCallTreeLoaded(calls.get(i).getCallTree() != null);
            }
            
            seq.add(item);
        }
        
        return seq;
    }
    
    private List<EnhancedDebugAnalysisResult.CrossRequestCallChain> buildCrossRequestChains(
            List<DebugAnalysisResult.ApiCall> calls) {
        
        List<EnhancedDebugAnalysisResult.CrossRequestCallChain> chains = new ArrayList<>();
        
        for (int i = 0; i < calls.size() - 1; i++) {
            DebugAnalysisResult.ApiCall curr = calls.get(i);
            DebugAnalysisResult.ApiCall next = calls.get(i + 1);
            
            EnhancedDebugAnalysisResult.CrossRequestCallChain chain =
                new EnhancedDebugAnalysisResult.CrossRequestCallChain();
            
            chain.setFromUrl(curr.getUrl());
            chain.setToUrl(next.getUrl());
            chain.setFromSeq(i + 1);
            chain.setToSeq(i + 2);
            
            if (curr.getRecommendedRepo() != null && next.getRecommendedRepo() != null) {
                boolean sameRepo = curr.getRecommendedRepo().getRepoId()
                    .equals(next.getRecommendedRepo().getRepoId());
                
                chain.setCrossRepo(!sameRepo);
                chain.setRelationshipType(sameRepo ? "SAME_REPO" : "CROSS_REPO");
                chain.setDescription(sameRepo ? "同仓库" : "跨仓库");
                chain.setFromRepo(curr.getRecommendedRepo().getRepoName());
                chain.setToRepo(next.getRecommendedRepo().getRepoName());
            } else {
                chain.setRelationshipType("UNKNOWN");
                chain.setDescription("未知");
            }
            
            chains.add(chain);
        }
        
        return chains;
    }
    
    // ==================== Task #7: 请求链对比功能 ====================
    
    @Override
    public RequestChainAnalyzer.ComparisonResult compareChains(List<RequestChainDTO> chains) {
        if (chains == null || chains.size() < 2) {
            throw new IllegalArgumentException("至少需要2个请求链才能进行对比");
        }
        
        logger.info("[请求链对比] 对比 {} 个请求链", chains.size());
        
        // 1. 分析每个请求链
        List<DebugAnalysisResult> results = new ArrayList<>();
        for (RequestChainDTO chain : chains) {
            results.add(analyze(chain));
        }
        
        // 2. 生成每个请求链的摘要
        List<RequestChainAnalyzer.ChainSummary> summaries = new ArrayList<>();
        for (int i = 0; i < chains.size(); i++) {
            RequestChainDTO chain = chains.get(i);
            DebugAnalysisResult result = results.get(i);
            
            long totalDuration = chain.getRequestChain().stream()
                .mapToLong(r -> r.getDuration() != null ? r.getDuration() : 0)
                .sum();
            
            int failedCount = (int) chain.getRequestChain().stream()
                .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
                .count();
            
            // 统计外部调用
            int dbCalls = 0, httpCalls = 0;
            for (DebugAnalysisResult.ApiCall call : result.getApiCalls()) {
                if (call.getCallTree() != null) {
                    Map<String, Integer> counts = countBoundaries(call.getCallTree());
                    dbCalls += counts.getOrDefault("DB", 0);
                    httpCalls += counts.getOrDefault("HTTP", 0);
                }
            }
            
            // 计算性能评分
            int score = calculatePerformanceScore(totalDuration, failedCount, dbCalls, httpCalls, 
                chain.getRequestChain().size());
            
            summaries.add(new RequestChainAnalyzer.ChainSummary(
                i + 1,
                chain.getSessionId() != null ? String.valueOf(chain.getSessionId()) : "请求链" + (i + 1),
                totalDuration,
                chain.getRequestChain().size(),
                failedCount,
                dbCalls,
                httpCalls,
                score
            ));
        }
        
        // 3. 计算性能差异
        List<RequestChainAnalyzer.PerformanceDiff> diffs = new ArrayList<>();
        
        // 总耗时差异
        diffs.add(calculateMetricDiff("总耗时 (ms)", 
            summaries.stream().map(s -> s.totalDuration()).toList()));
        
        // 数据库调用差异
        diffs.add(calculateMetricDiff("数据库调用次数", 
            summaries.stream().map(s -> (long) s.dbCallCount()).toList()));
        
        // HTTP调用差异
        diffs.add(calculateMetricDiff("HTTP调用次数", 
            summaries.stream().map(s -> (long) s.httpCallCount()).toList()));
        
        // 失败请求差异
        diffs.add(calculateMetricDiff("失败请求数", 
            summaries.stream().map(s -> (long) s.failedCount()).toList()));
        
        // 性能评分差异
        diffs.add(calculateMetricDiff("性能评分", 
            summaries.stream().map(s -> (long) s.score()).toList()));
        
        // 4. 生成洞察
        List<String> insights = generateComparisonInsights(summaries, diffs);
        
        // 5. 生成报告
        String report = generateComparisonReport(summaries, diffs, insights);
        
        // 6. 生成摘要
        String summary = String.format("对比了 %d 个请求链，发现 %d 项显著差异", 
            chains.size(), 
            (int) diffs.stream().filter(d -> d.variance() > 0.1).count());
        
        return new RequestChainAnalyzer.ComparisonResult(
            summary,
            summaries,
            diffs,
            insights,
            report
        );
    }
    
    /**
     * 计算性能评分
     */
    private int calculatePerformanceScore(long totalDuration, int failedCount, 
                                         int dbCalls, int httpCalls, int totalRequests) {
        int score = 100;
        
        // 总耗时扣分
        if (totalDuration > 5000) score -= 30;
        else if (totalDuration > 2000) score -= 15;
        else if (totalDuration > 1000) score -= 5;
        
        // 失败请求扣分
        score -= Math.min(failedCount * 15, 30);
        
        // 数据库调用扣分
        if (dbCalls > 50) score -= 20;
        else if (dbCalls > 20) score -= 10;
        else if (dbCalls > 10) score -= 5;
        
        // HTTP调用扣分
        if (httpCalls > 20) score -= 15;
        else if (httpCalls > 10) score -= 10;
        else if (httpCalls > 5) score -= 5;
        
        return Math.max(score, 0);
    }
    
    /**
     * 计算指标差异
     */
    private RequestChainAnalyzer.PerformanceDiff calculateMetricDiff(String metric, List<Long> values) {
        if (values.isEmpty()) {
            return new RequestChainAnalyzer.PerformanceDiff(metric, values, 0, 0, 0, 0, "无数据");
        }
        
        long min = values.stream().min(Long::compareTo).orElse(0L);
        long max = values.stream().max(Long::compareTo).orElse(0L);
        double avg = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        
        // 计算方差（变异系数）
        double variance = 0;
        if (avg > 0) {
            double sumSquaredDiff = 0;
            for (Long value : values) {
                double diff = value - avg;
                sumSquaredDiff += diff * diff;
            }
            double stdDev = Math.sqrt(sumSquaredDiff / values.size());
            variance = stdDev / avg;  // 变异系数
        }
        
        // 分析
        String analysis;
        if (variance < 0.1) {
            analysis = "✅ 性能稳定";
        } else if (variance < 0.3) {
            analysis = "⚠️ 有一定波动";
        } else {
            analysis = String.format("❌ 波动较大 (最大值是最小值的 %.1f 倍)", max / (double) Math.max(min, 1));
        }
        
        return new RequestChainAnalyzer.PerformanceDiff(metric, values, min, max, avg, variance, analysis);
    }
    
    /**
     * 生成对比洞察
     */
    private List<String> generateComparisonInsights(List<RequestChainAnalyzer.ChainSummary> summaries,
                                                    List<RequestChainAnalyzer.PerformanceDiff> diffs) {
        List<String> insights = new ArrayList<>();
        
        // 找出最快和最慢的请求链
        RequestChainAnalyzer.ChainSummary fastest = summaries.stream()
            .min((a, b) -> Long.compare(a.totalDuration(), b.totalDuration()))
            .orElse(null);
        RequestChainAnalyzer.ChainSummary slowest = summaries.stream()
            .max((a, b) -> Long.compare(a.totalDuration(), b.totalDuration()))
            .orElse(null);
        
        if (fastest != null && slowest != null && fastest != slowest) {
            long timeDiff = slowest.totalDuration() - fastest.totalDuration();
            double pctDiff = (timeDiff / (double) fastest.totalDuration()) * 100;
            
            if (pctDiff > 50) {
                insights.add(String.format("⚠️ **性能差异显著**：最慢的请求链#%d比最快的#%d慢了%.0f%% (%dms vs %dms)",
                    slowest.index(), fastest.index(), pctDiff, slowest.totalDuration(), fastest.totalDuration()));
                
                // 分析原因
                int dbDiff = slowest.dbCallCount() - fastest.dbCallCount();
                int httpDiff = slowest.httpCallCount() - fastest.httpCallCount();
                
                if (dbDiff > 5) {
                    insights.add(String.format("   → 可能原因：数据库调用多了 %d 次", dbDiff));
                }
                if (httpDiff > 3) {
                    insights.add(String.format("   → 可能原因：HTTP调用多了 %d 次", httpDiff));
                }
            }
        }
        
        // 找出评分差异
        RequestChainAnalyzer.ChainSummary bestScore = summaries.stream()
            .max((a, b) -> Integer.compare(a.score(), b.score()))
            .orElse(null);
        RequestChainAnalyzer.ChainSummary worstScore = summaries.stream()
            .min((a, b) -> Integer.compare(a.score(), b.score()))
            .orElse(null);
        
        if (bestScore != null && worstScore != null && bestScore != worstScore) {
            int scoreDiff = bestScore.score() - worstScore.score();
            if (scoreDiff > 20) {
                insights.add(String.format("📊 **评分差异**：请求链#%d得分最高(%d分)，请求链#%d得分最低(%d分)",
                    bestScore.index(), bestScore.score(), worstScore.index(), worstScore.score()));
            }
        }
        
        // 检查稳定性
        RequestChainAnalyzer.PerformanceDiff durationDiff = diffs.stream()
            .filter(d -> d.metric().contains("总耗时"))
            .findFirst()
            .orElse(null);
        
        if (durationDiff != null && durationDiff.variance() < 0.1) {
            insights.add("✅ **性能稳定**：多次请求的耗时波动很小，系统表现一致");
        } else if (durationDiff != null && durationDiff.variance() > 0.3) {
            insights.add("⚠️ **性能不稳定**：不同请求的耗时波动较大，建议检查缓存命中率或外部依赖");
        }
        
        return insights;
    }
    
    /**
     * 生成对比报告
     */
    private String generateComparisonReport(List<RequestChainAnalyzer.ChainSummary> summaries,
                                           List<RequestChainAnalyzer.PerformanceDiff> diffs,
                                           List<String> insights) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 请求链对比分析报告\n\n");
        
        // 关键洞察
        if (!insights.isEmpty()) {
            sb.append("## 🔍 关键发现\n\n");
            insights.forEach(insight -> sb.append(insight).append("\n\n"));
        }
        
        // 性能对比表
        sb.append("## 📊 性能对比\n\n");
        sb.append("| 请求链 | 总耗时 | 请求数 | 失败数 | DB调用 | HTTP调用 | 评分 |\n");
        sb.append("|--------|--------|--------|--------|--------|----------|------|\n");
        
        for (RequestChainAnalyzer.ChainSummary summary : summaries) {
            String scoreEmoji = summary.score() >= 80 ? "🟢" : summary.score() >= 60 ? "🟡" : "🔴";
            sb.append(String.format("| #%d | %dms | %d | %d | %d | %d | %s %d |\n",
                summary.index(),
                summary.totalDuration(),
                summary.requestCount(),
                summary.failedCount(),
                summary.dbCallCount(),
                summary.httpCallCount(),
                scoreEmoji,
                summary.score()));
        }
        
        sb.append("\n");
        
        // 指标差异分析
        sb.append("## 📈 指标差异分析\n\n");
        
        for (RequestChainAnalyzer.PerformanceDiff diff : diffs) {
            sb.append(String.format("### %s\n\n", diff.metric()));
            sb.append(String.format("- **范围**: %d ~ %d\n", diff.minValue(), diff.maxValue()));
            sb.append(String.format("- **平均**: %.1f\n", diff.avgValue()));
            sb.append(String.format("- **分析**: %s\n\n", diff.analysis()));
        }
        
        // 优化建议
        sb.append("## 💡 优化建议\n\n");
        
        // 找出性能最差的请求链
        RequestChainAnalyzer.ChainSummary worst = summaries.stream()
            .min((a, b) -> Integer.compare(a.score(), b.score()))
            .orElse(null);
        
        if (worst != null && worst.score() < 70) {
            sb.append(String.format("**针对请求链#%d（评分：%d）：**\n\n", worst.index(), worst.score()));
            
            if (worst.dbCallCount() > 20) {
                sb.append("- 🗄️ 数据库调用过多，考虑添加缓存或合并查询\n");
            }
            if (worst.httpCallCount() > 10) {
                sb.append("- 🌐 HTTP调用过多，考虑批量请求或服务合并\n");
            }
            if (worst.totalDuration() > 2000) {
                sb.append("- ⏱️ 总耗时过长，建议异步处理非关键路径\n");
            }
            if (worst.failedCount() > 0) {
                sb.append("- ❌ 存在失败请求，需要排查错误原因\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
}
