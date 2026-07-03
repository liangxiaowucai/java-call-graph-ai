package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.repository.ApiEndpointRepo;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.EmbeddingService;
import com.adrninistrator.javacg2.platform.service.RepoDataStore;
import com.adrninistrator.javacg2.platform.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class QAEngineImpl {

    private static final Logger logger = LoggerFactory.getLogger(QAEngineImpl.class);

    private final ClaudeApiClient claudeClient;
    private final CallGraphEngine callGraphEngine;
    private final CallChainCodeGenerator codeGenerator;
    private final ApiEndpointRepo apiEndpointRepo;
    private final BoundaryRepo boundaryRepo;
    private final ChunkRepo chunkRepo;
    private final SystemConfigRepo configRepo;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final com.adrninistrator.javacg2.platform.repository.RepositoryRepo repositoryRepo;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final CodeAnalysisToolExecutor toolExecutor;
    private final RepoDataStore repoDataStore;
    private final com.adrninistrator.javacg2.platform.service.PromptService promptService;

    // 统一的默认 system prompt，贴合 java-callgraph2 平台的数据能力
    public static final String DEFAULT_SYSTEM_PROMPT = ""
            + "你是一个企业级 Java 项目的代码分析助手，服务于开发团队的产品经理、研发工程师和测试人员。\n"
            + "你的所有回答必须严格基于提供的真实源码和调用链数据，不得超出这个范围。\n\n"
            + "## 核心铁律（绝对不可违反）\n"
            + "1. **只说代码里有的**：每一句回答都必须能在提供的源码中找到依据，引用格式：`类名.方法名`\n"
            + "2. **没有就说没有**：源码中不存在的内容，直接回答\"源码中未发现XXX\"，不补充\"建议\"、\"通常\"、\"一般来说\"等推断性内容\n"
            + "3. **禁止编造**：不编造不存在的类、方法、字段、配置、注解、逻辑。禁止使用\"可能\"、\"通常\"、\"一般来说\"、\"建议\"（除非源码中有明确依据）\n"
            + "4. **版本准确**：代码示例必须基于项目技术栈的实际版本 API\n\n"
            + "## 自动识别用户输入类型\n"
            + "- **纯文字问题**：直接基于源码回答\n"
            + "- **JSON 请求体**：对照入参实体类逐字段校验，标注必填字段是否缺失、类型是否正确、多余字段是否存在\n"
            + "- **JSON 响应体**：对照源码分析返回结果的来源、错误码含义、哪个分支产生了这个结果\n"
            + "- **异常堆栈日志**：对照源码定位异常发生在哪个方法、什么原因、如何修复\n"
            + "- **混合内容**：分别提取各部分内容分析\n\n"
            + "## 回答策略\n\n"
            + "### 功能说明类\n"
            + "1. 用一句话概括接口功能（只根据源码中的方法名、注释、日志描述，不推断）\n"
            + "2. 按调用顺序列出关键步骤，每步标注来源 `类名.方法名`\n"
            + "3. 标注源码中明确出现的外部依赖（HTTP/gRPC/MQ/DB）\n\n"
            + "### 限制和校验类\n"
            + "只列出源码中明确存在的以下内容：\n"
            + "- 注解：@NotNull, @NotBlank, @NotEmpty, @Size, @Length, @Max, @Min, @Pattern, @Valid\n"
            + "- 条件判断：if + throw、if + return 的具体条件和值\n"
            + "- 配置值：从配置文件读取的限制值\n"
            + "以上均未找到时，输出：`源码中未发现XXX限制`\n\n"
            + "### 对接指南类\n"
            + "1. HTTP 方法、URL、Content-Type（来自源码注解）\n"
            + "2. 入参字段表格：字段名 | 类型 | 必填 | 说明（只列出源码中存在的字段，必填/限制只填写有注解或 if 判断约束的，没有就填\"-\"）\n"
            + "3. 请求示例：字段名使用源码中的实际字段名，示例值只能使用源码中出现的枚举常量或字面量，无法确定的值填 \"<待确认>\"\n"
            + "4. 返回结构：只描述源码中明确构造的返回字段\n"
            + "5. 错误码：只列出源码中 throw 语句明确抛出的异常\n\n"
            + "### 排错类\n"
            + "1. 定位异常抛出点（类名.方法名 + 行号，来自源码）\n"
            + "2. 分析触发条件（源码中 throw 前面的 if 条件）\n"
            + "3. 错误码含义（只引用源码中的枚举定义）\n"
            + "4. 追溯调用链，找到根因\n\n"
            + "### 架构和依赖类\n"
            + "1. 只列出源码中明确出现的外部调用（HTTP/gRPC/MQ），标注实际 URL 或 Topic\n"
            + "2. 只列出源码中明确出现的数据库操作，标注表名和操作类型\n"
            + "3. 数据流向只描述源码中可以追踪的路径\n"
            + "4. 事务边界只标注源码中有 @Transactional 注解的方法\n\n"
            + "### 外部调用展示格式（HTTP/gRPC）——必须严格遵守\n"
            + "工具返回的「外部调用」条目已装配好 系统名+完整URL+用途，请逐条原样列出，不要只写方法名一笔带过，不要遗漏任何一条。\n"
            + "格式：`**系统名**：HTTP调用 \\`完整URL\\` 用途说明`，例如：\n"
            + "- **订单系统**：HTTP调用 `http://order-service.example.com/api/order/detail` 获取订单详情\n"
            + "URL 必须是 base+path 拼好的完整地址（不能只有 host）；系统名结合工具给的候选名与方法/类注释润色成「XX系统」，实在无依据时用配置 key 或 host；用途取自方法注释/摘要，没有就省略。\n\n"
            + "### 注意事项类\n"
            + "只报告源码中有代码依据的风险点，找不到就跳过该项，不输出\"未发现\"的逐条列举：\n"
            + "- 空指针：无 null 检查直接调用的具体代码行\n"
            + "- 吞异常：catch 块为空或只有日志的具体位置\n"
            + "- 事务失效：同类内部调用 @Transactional 方法的具体位置\n"
            + "- 外部调用：源码中可见的无超时配置的 HTTP/gRPC 调用\n\n"
            + "## 回答格式\n"
            + "- 用中文回答\n"
            + "- Markdown 格式：标题(##)、列表(-)、代码块(```)、表格(|)、加粗(**)\n"
            + "- 每个结论引用来源：`类名.方法名`\n"
            + "- 调用链用 A → B → C 表示\n"
            + "- 源码中有中文注释或日志消息，直接引用\n"
            + "- 源码中确实没有的内容，直接说明，不补充建议\n";

    // 回答缓存: repoId + method + question hash → answer（带 TTL 30min，防止代码变更后旧缓存长期命中）
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private final Map<String, CachedAnswer> answerCache = new ConcurrentHashMap<>();

    public QAEngineImpl(ClaudeApiClient claudeClient, CallGraphEngine callGraphEngine,
                         CallChainCodeGenerator codeGenerator, ApiEndpointRepo apiEndpointRepo,
                         BoundaryRepo boundaryRepo, ChunkRepo chunkRepo, SystemConfigRepo configRepo,
                         ProjectInfoExtractor projectInfoExtractor,
                         com.adrninistrator.javacg2.platform.repository.RepositoryRepo repositoryRepo,
                         EmbeddingService embeddingService,
                         VectorStoreService vectorStoreService,
                         CodeAnalysisToolExecutor toolExecutor,
                         RepoDataStore repoDataStore,
                         com.adrninistrator.javacg2.platform.service.PromptService promptService) {
        this.claudeClient = claudeClient;
        this.callGraphEngine = callGraphEngine;
        this.codeGenerator = codeGenerator;
        this.apiEndpointRepo = apiEndpointRepo;
        this.boundaryRepo = boundaryRepo;
        this.chunkRepo = chunkRepo;
        this.configRepo = configRepo;
        this.projectInfoExtractor = projectInfoExtractor;
        this.repositoryRepo = repositoryRepo;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.toolExecutor = toolExecutor;
        this.repoDataStore = repoDataStore;
        this.promptService = promptService;
    }

    /**
     * 搜索接口（模糊匹配注释、URL、方法名、类名）
     */
    public List<EndpointSearchResult> searchEndpoints(Long repoId, String keyword) {
        // 走 RepoDataStore 缓存，避免每次全表查询
        RepoDataStore.RepoData cache = repoDataStore.get(repoId);
        List<ApiEndpointEntity> all = new ArrayList<>(cache.endpointMap().values());
        if (keyword == null || keyword.isBlank()) return all.stream().map(this::toSearchResult).collect(Collectors.toList());

        String lower = keyword.toLowerCase();
        String[] words = lower.split("\\s+");

        // 从缓存 chunkMap 获取注释/摘要信息，扩大搜索范围
        Map<String, String> chunkSummaries = new HashMap<>();
        for (var chunk : cache.chunkMap().values()) {
            StringBuilder text = new StringBuilder();
            if (chunk.getCallSummary() != null) text.append(chunk.getCallSummary()).append(" ");
            if (chunk.getAnnotations() != null) text.append(chunk.getAnnotations()).append(" ");
            if (chunk.getClassName() != null) text.append(chunk.getClassName()).append(" ");
            chunkSummaries.put(chunk.getFullMethod(), text.toString().toLowerCase());
        }

        return all.stream()
                .map(e -> {
                    EndpointSearchResult r = toSearchResult(e);
                    int score = 0;
                    // 搜索 URL + 类名 + 方法名
                    String searchText = ((r.urlPath != null ? r.urlPath : "") + " " + r.className + " " + r.methodName + " " + r.comment).toLowerCase();
                    // 加上 chunks 表的摘要
                    String chunkText = chunkSummaries.getOrDefault(e.getFullMethod(), "");
                    String fullSearchText = searchText + " " + chunkText;

                    for (String word : words) {
                        if (fullSearchText.contains(word)) score += 10;
                        // 部分匹配也给分（如"词云"匹配"wordcloud"）
                        if (r.className.toLowerCase().contains(word)) score += 5;
                        if (r.methodName.toLowerCase().contains(word)) score += 5;
                    }
                    r.score = score;
                    return r;
                })
                .filter(r -> r.score > 0)
                .sorted((a, b) -> b.score - a.score)
                .collect(Collectors.toList());
    }

    // AI rerank 结果低于此分数时需要用户确认
    private static final int AI_RERANK_MIN_SCORE = 30;

    /**
     * 智能问答：仓库定位 → AI 提取关键词 → 关键词粗筛 → AI rerank 兜底 → 加载代码 → AI 回答
     * <p>
     * 当置信度不足时（关键词粗筛无结果触发 AI rerank，或 top 分数 < CONFIDENCE_THRESHOLD），
     * 返回 needsConfirmation=true + 候选接口列表，由用户选择后携带 confirmedMethods 重新请求。
     *
     * @param confirmedMethods 用户已确认的接口 fullMethod 列表，非空时跳过匹配直接生成回答
     */
    public SmartQAResponse smartAsk(List<Long> repoIds, String question, List<String> confirmedMethods,
                                     IntentConfirmation confirmedIntent) {
        logger.info("[智能问答] 问题: {}, 仓库: {}", question, repoIds);

        // 如果前端没传 repoIds，自动查所有 READY 仓库
        if (repoIds == null || repoIds.isEmpty()) {
            repoIds = repositoryRepo.findAll().stream()
                    .filter(r -> "READY".equals(r.getStatus()))
                    .map(r -> r.getId())
                    .collect(Collectors.toList());
        }

        // ========== 第零步：仓库定位（用 profile 粗筛） ==========
        List<Long> targetRepoIds = locateRepos(repoIds, question);
        logger.info("[智能问答] 仓库定位: {} -> {}", repoIds, targetRepoIds);

        List<Long> idsToLoad = (confirmedMethods != null && !confirmedMethods.isEmpty()) ? repoIds : targetRepoIds;

        // 收集目标仓库的接口和摘要
        List<RepoEndpoints> repoEndpointsList = new ArrayList<>();
        for (Long repoId : idsToLoad) {
            var repo = repositoryRepo.findById(repoId).orElse(null);
            if (repo == null || !"READY".equals(repo.getStatus())) continue;
            // 走 RepoDataStore 缓存，避免每次全表查询
            RepoDataStore.RepoData cache = repoDataStore.get(repoId);
            List<ApiEndpointEntity> endpoints = new ArrayList<>(cache.endpointMap().values());
            Map<String, String> summaries = new HashMap<>();
            for (var chunk : cache.chunkMap().values()) {
                if (chunk.getCallSummary() != null && !chunk.getCallSummary().isBlank()) {
                    summaries.put(chunk.getFullMethod(), chunk.getCallSummary().toLowerCase());
                }
            }
            // 扩展搜索范围：除了 api_endpoint 入口点，也包含 call_summary 中包含关键词的 Service/Mapper 方法
            // 这些方法虽然不是 HTTP 入口点，但可能是用户想了解的业务方法
            repoEndpointsList.add(new RepoEndpoints(repoId, repo.getName(), endpoints, summaries));
        }

        if (repoEndpointsList.isEmpty()) {
            return new SmartQAResponse("暂无已分析的仓库。", List.of(), false, List.of(), List.of(), false, null, false);
        }

        // ========== 用户已确认接口：跳过匹配，直接生成回答 ==========
        if (confirmedMethods != null && !confirmedMethods.isEmpty()) {
            List<String> keywords = extractKeywordsFallback(question);
            List<ScoredEndpoint> confirmedResults = resolveConfirmedMethods(repoEndpointsList, confirmedMethods);
            if (confirmedResults.isEmpty()) {
                return new SmartQAResponse("未找到已确认的接口，请重新选择。", List.of(), false, keywords, List.of(), false, null, false);
            }
            logger.info("[智能问答] 用户确认接口: {}", confirmedMethods);
            List<MatchedEndpoint> matchedEndpoints = confirmedResults.stream().map(this::toMatchedEndpoint).collect(Collectors.toList());
            return generateAnswer(confirmedResults, question, keywords, matchedEndpoints);
        }

        // ========== 第一步：AI 提取意图 ==========
        IntentResult intent;
        if (confirmedIntent != null) {
            // 用户已确认/修改意图，重新提炼关键词
            intent = refineIntent(question, confirmedIntent);
            logger.info("[智能问答] 用户确认意图: {} ({})", intent.intentType(), intent.summary());
        } else {
            intent = extractIntent(question);
            logger.info("[智能问答] AI提取意图: {} ({}), 置信度: {}", intent.intentType(), intent.summary(), intent.confidence());
            // 置信度不足 → 返回意图确认卡，等待用户确认后再继续
            if (intent.confidence() < 70) {
                logger.info("[智能问答] 意图置信度不足({}), 返回确认卡", intent.confidence());
                return new SmartQAResponse(
                        "请确认我对您问题的理解是否正确：",
                        List.of(), false, intent.keywords(), List.of(), false,
                        intent, true);
            }
        }

        // ========== 第二步：语义检索（向量召回，降级到关键词粗筛） ==========
        List<ScoredEndpoint> candidates = semanticSearch(repoEndpointsList, question, intent.keywords(), 30);
        logger.info("[智能问答] 语义检索: {} 个命中, topScore={}",
                candidates.size(), candidates.isEmpty() ? 0 : candidates.get(0).score);

        // ========== 第三步：始终走 AI rerank（语义匹配，不依赖字符串匹配） ==========
        // 只有当粗筛 top 分数极高（URL/类名精确命中）时才跳过 AI rerank
        boolean skipAiRerank = !candidates.isEmpty() && candidates.get(0).score >= 80;
        List<ScoredEndpoint> finalCandidates;
        boolean usedAiRerank;

        if (skipAiRerank) {
            // 精确命中，但仍需扩展搜索同仓库内其他相关接口（避免遗漏）
            // 策略：用精确命中的接口的类名前缀，在同仓库内搜索同业务域的其他接口
            Set<String> hitClassPrefixes = new HashSet<>();
            Set<Long> hitRepoIds = new HashSet<>();
            for (ScoredEndpoint se : candidates.stream().limit(5).collect(Collectors.toList())) {
                hitRepoIds.add(se.repoId);
                String cls = se.endpoint.getClassName();
                if (cls != null) {
                    // 提取包路径前缀（如 com.xxx.goods → 同包下的其他 Controller 也应该被包含）
                    int lastDot = cls.lastIndexOf('.');
                    if (lastDot > 0) hitClassPrefixes.add(cls.substring(0, lastDot));
                }
            }

            // 扩展：在命中的仓库中，找同包下的所有接口
            List<ScoredEndpoint> expanded = new ArrayList<>(candidates.stream().limit(10).collect(Collectors.toList()));
            Set<String> alreadyIncluded = expanded.stream().map(se -> se.endpoint.getFullMethod()).collect(Collectors.toSet());

            for (RepoEndpoints re : repoEndpointsList) {
                if (!hitRepoIds.contains(re.repoId)) continue;
                for (ApiEndpointEntity ep : re.endpoints) {
                    if (alreadyIncluded.contains(ep.getFullMethod())) continue;
                    String epClass = ep.getClassName();
                    if (epClass == null) continue;
                    // 同包前缀的接口也加入
                    for (String prefix : hitClassPrefixes) {
                        if (epClass.startsWith(prefix)) {
                            String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");
                            expanded.add(new ScoredEndpoint(ep, re.repoId, re.repoName, 60, summary));
                            alreadyIncluded.add(ep.getFullMethod());
                            break;
                        }
                    }
                    // URL 路径中包含相同业务关键词段的也加入（如 /goods/* 命中了，/backend/goods/* 也应该包含）
                    if (ep.getUrlPath() != null) {
                        Set<String> epSegments = extractUrlSegments(ep.getUrlPath());
                        for (ScoredEndpoint hit : candidates.stream().limit(5).collect(Collectors.toList())) {
                            if (hit.endpoint.getUrlPath() != null && !alreadyIncluded.contains(ep.getFullMethod())) {
                                Set<String> hitSegments = extractUrlSegments(hit.endpoint.getUrlPath());
                                // 如果有共同的业务段（排除通用段如 api, v1, v2）
                                Set<String> commonSegments = new HashSet<>(epSegments);
                                commonSegments.retainAll(hitSegments);
                                commonSegments.removeAll(Set.of("api", "v1", "v2", "v3", "admin", "internal"));
                                if (!commonSegments.isEmpty()) {
                                    String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");
                                    expanded.add(new ScoredEndpoint(ep, re.repoId, re.repoName, 55, summary));
                                    alreadyIncluded.add(ep.getFullMethod());
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // 同时用关键词在同仓库全量接口中再扫一遍（捕获不同 URL 前缀但业务相关的接口）
            for (RepoEndpoints re : repoEndpointsList) {
                if (!hitRepoIds.contains(re.repoId)) continue;
                for (ApiEndpointEntity ep : re.endpoints) {
                    if (alreadyIncluded.contains(ep.getFullMethod())) continue;
                    String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");
                    String fullText = ((ep.getUrlPath() != null ? ep.getUrlPath() : "") + " "
                            + (ep.getClassName() != null ? ep.getClassName() : "") + " " + summary).toLowerCase();
                    // 用核心业务关键词（长度>=3）再匹配一次
                    int extraScore = 0;
                    for (String kw : effectiveKeywordsForExpand(intent.keywords())) {
                        if (fullText.contains(kw)) extraScore += 10;
                    }
                    if (extraScore >= 20) { // 至少匹配 2 个核心关键词
                        expanded.add(new ScoredEndpoint(ep, re.repoId, re.repoName, extraScore, summary));
                        alreadyIncluded.add(ep.getFullMethod());
                    }
                }
            }

            finalCandidates = expanded;
            usedAiRerank = false;
            // 扩展后结果太多时，用 AI rerank 精选（避免不相关接口混入 top 3）
            if (finalCandidates.size() > 10) {
                logger.info("[智能问答] 扩展结果过多（{}个），启用 AI rerank 精选", finalCandidates.size());
                List<RepoEndpoints> expandedPool = buildCandidatePool(repoEndpointsList, finalCandidates);
                List<ScoredEndpoint> aiSelected = aiRerank(expandedPool, question, intent.keywords());
                if (!aiSelected.isEmpty()) {
                    finalCandidates = aiSelected;
                    usedAiRerank = true;
                }
            }
            logger.info("[智能问答] 精确命中 + 扩展搜索：{} 个接口", finalCandidates.size());
        } else {
            // 语义匹配：用 AI 从候选池（或全量）中选择最相关的接口
            usedAiRerank = true;
            if (candidates.isEmpty()) {
                // 粗筛完全无结果，用全量接口做 AI rerank
                logger.info("[智能问答] 粗筛无结果，全量 AI rerank");
                finalCandidates = aiRerank(repoEndpointsList, question, intent.keywords());
            } else {
                // 粗筛有结果但不够精确，用粗筛 top 30 做 AI rerank
                logger.info("[智能问答] 粗筛结果不够精确（topScore={}），AI rerank 候选池", candidates.get(0).score);
                // 构建候选池的 RepoEndpoints（只包含粗筛命中的接口）
                List<RepoEndpoints> candidatePool = buildCandidatePool(repoEndpointsList, candidates);
                finalCandidates = aiRerank(candidatePool, question, intent.keywords());
                // 如果 AI rerank 也没选出来，回退到粗筛 top 结果
                if (finalCandidates.isEmpty()) {
                    finalCandidates = candidates.stream().limit(5).collect(Collectors.toList());
                    usedAiRerank = false;
                }
            }
            logger.info("[智能问答] AI rerank: {} 个命中", finalCandidates.size());
        }

        candidates = finalCandidates;

        if (candidates.isEmpty()) {
            return new SmartQAResponse(
                    "🔍 未找到与您问题相关的接口。\n\n"
                    + "**建议：**\n"
                    + "1. 在左侧选择仓库，手动搜索接口\n"
                    + "2. 尝试用更具体的接口名称或 URL 提问\n"
                    + "3. 确认相关仓库已完成分析",
                    List.of(), false, intent.keywords(), List.of(), false, null, false);
        }

        // ========== 置信度检查：多个相关接口时分组展示，让用户选择 ==========
        int topScore = candidates.isEmpty() ? 0 : candidates.get(0).score;
        // AI rerank 选出的结果置信度充足时直接回答
        // 但如果候选数量 > 3 且分数接近（说明多个接口都相关），返回分组列表让用户选
        boolean multipleRelevant = candidates.size() > 3
                && candidates.size() >= 2
                && candidates.get(candidates.size() - 1).score >= candidates.get(0).score * 0.6;
        boolean needsConfirmation = candidates.isEmpty()
                || multipleRelevant
                || (!skipAiRerank && usedAiRerank && topScore < AI_RERANK_MIN_SCORE);

        if (needsConfirmation) {
            logger.info("[智能问答] 需要用户确认（multipleRelevant={}, aiRerank={}, topScore={}）", multipleRelevant, usedAiRerank, topScore);
            // 当候选数量较多（>5）且问题是概览性质时，直接生成模块概览回答
            // 而不是让用户手动勾选
            if (multipleRelevant && candidates.size() > 5) {
                logger.info("[智能问答] 候选较多（{}个），生成模块概览回答", candidates.size());
                return generateOverviewAnswer(candidates, question, intent);
            }
            List<MatchedEndpoint> candidateEndpoints = candidates.stream()
                    .map(this::toMatchedEndpoint)
                    .collect(Collectors.toList());
            String confirmMsg = "找到以下可能相关的接口，请选择你想了解的（可多选）：";
            return new SmartQAResponse(
                    confirmMsg,
                    List.of(), false, intent.keywords(), candidateEndpoints, true, null, false);
        }

        // ========== 第四步：候选数量 <= 3，全部加载源码生成回答 ==========
        List<ScoredEndpoint> topResults = candidates.stream().limit(3).collect(Collectors.toList());
        List<MatchedEndpoint> matchedEndpoints = topResults.stream().map(this::toMatchedEndpoint).collect(Collectors.toList());
        return generateAnswer(topResults, question, intent.keywords(), matchedEndpoints);
    }

    /**
     * 智能问答的中间阶段：完成召回+rerank，但不调 AI 生成最终答案。
     * 供 SSE Controller 使用，拿到 topResults 后再调 generateAnswerWithLoop。
     */
    public SmartQAIntermediate smartAskIntermediate(List<Long> repoIds, String question,
                                                     List<String> confirmedMethods,
                                                     IntentConfirmation confirmedIntent) {
        SmartQAResponse fullResponse = smartAsk(repoIds, question, confirmedMethods, confirmedIntent);

        // 如果需要确认，直接透传
        if (fullResponse.needsConfirmation() || fullResponse.needsIntentConfirmation()) {
            return new SmartQAIntermediate(
                    fullResponse.answer(), fullResponse.keywords(),
                    fullResponse.matchedEndpoints(), List.of(),
                    fullResponse.needsConfirmation(), fullResponse.needsIntentConfirmation(),
                    fullResponse.intentResult());
        }

        // 正常路径：topResults 从 matchedEndpoints 反查
        List<ScoredEndpoint> topResults = resolveMatchedToScored(repoIds, fullResponse.matchedEndpoints());
        return new SmartQAIntermediate(
                null, fullResponse.keywords(),
                fullResponse.matchedEndpoints(), topResults,
                false, false, null);
    }

    /** 根据 matchedEndpoints 反查 ScoredEndpoint（用于 SSE 路径） */
    private List<ScoredEndpoint> resolveMatchedToScored(List<Long> repoIds, List<MatchedEndpoint> matched) {
        List<ScoredEndpoint> result = new ArrayList<>();
        for (MatchedEndpoint me : matched) {
            for (Long repoId : repoIds) {
                List<ApiEndpointEntity> eps = apiEndpointRepo.findByRepoId(repoId);
                for (ApiEndpointEntity ep : eps) {
                    if (ep.getFullMethod().equals(me.fullMethod())) {
                        var repo = repositoryRepo.findById(repoId).orElse(null);
                        String repoName = repo != null ? repo.getName() : "";
                        result.add(new ScoredEndpoint(ep, repoId, repoName, me.score(), ""));
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** 根据 repoId + fullMethod 列表直接构建 ScoredEndpoint（ask 接口用） */
    public List<ScoredEndpoint> resolveMethodsToScored(Long repoId, List<String> methods) {
        List<ScoredEndpoint> result = new ArrayList<>();
        var repo = repositoryRepo.findById(repoId).orElse(null);
        String repoName = repo != null ? repo.getName() : "";
        for (String method : methods) {
            apiEndpointRepo.findByRepoId(repoId).stream()
                    .filter(ep -> ep.getFullMethod().equals(method))
                    .findFirst()
                    .ifPresentOrElse(
                            ep -> result.add(new ScoredEndpoint(ep, repoId, repoName, 100, "")),
                            () -> {
                                // 不在 api_endpoints 表也构造一个虚拟 endpoint
                                ApiEndpointEntity fake = new ApiEndpointEntity();
                                fake.setFullMethod(method);
                                fake.setEndpointType("METHOD");
                                int c = method.lastIndexOf(':');
                                if (c > 0) fake.setClassName(method.substring(0, c));
                                result.add(new ScoredEndpoint(fake, repoId, repoName, 100, ""));
                            });
        }
        return result;
    }

    /** 公开的 toMatchedEndpoint，供 Controller 调用 */
    public MatchedEndpoint toMatchedEndpointPublic(ScoredEndpoint se) {
        return toMatchedEndpoint(se);
    }

    /** SmartQAIntermediate：召回+rerank 完成后的中间结果 */
    public record SmartQAIntermediate(
            String answer,
            List<String> keywords,
            List<MatchedEndpoint> matchedEndpoints,
            List<ScoredEndpoint> topResults,
            boolean needsConfirmation,
            boolean needsIntentConfirmation,
            IntentResult intentResult) {}
    private List<ScoredEndpoint> resolveConfirmedMethods(List<RepoEndpoints> repoEndpointsList, List<String> confirmedMethods) {
        List<ScoredEndpoint> result = new ArrayList<>();
        for (RepoEndpoints re : repoEndpointsList) {
            for (ApiEndpointEntity ep : re.endpoints) {
                if (confirmedMethods.contains(ep.getFullMethod())) {
                    String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");
                    result.add(new ScoredEndpoint(ep, re.repoId, re.repoName, 100, summary));
                }
            }
        }
        return result;
    }

    /**
     * 仓库定位：用 profile 关键词匹配筛选目标仓库
     * 少于等于 5 个仓库时全搜，多于 5 个时用 profile 筛选
     */
    private List<Long> locateRepos(List<Long> repoIds, String question) {
        // 一次性查询所有仓库
        List<com.adrninistrator.javacg2.platform.entity.RepositoryEntity> allRepos = repositoryRepo.findAllById(repoIds);
        Map<Long, com.adrninistrator.javacg2.platform.entity.RepositoryEntity> repoMap = new HashMap<>();
        List<Long> readyRepoIds = new ArrayList<>();
        for (var repo : allRepos) {
            if ("READY".equals(repo.getStatus())) {
                readyRepoIds.add(repo.getId());
                repoMap.put(repo.getId(), repo);
            }
        }

        if (readyRepoIds.size() <= 5) return readyRepoIds;

        // 多仓库：用 profile 筛选
        String questionLower = question.toLowerCase();
        List<Map.Entry<Long, Integer>> scored = new ArrayList<>();

        for (Long id : readyRepoIds) {
            var repo = repoMap.get(id);
            String profile = repo.getProfile();
            if (profile == null || profile.isBlank()) {
                scored.add(Map.entry(id, 1));
                continue;
            }

            String profileLower = profile.toLowerCase();
            int score = 0;

            // 中文二字分词匹配
            for (int i = 0; i < questionLower.length() - 1; i++) {
                char c = questionLower.charAt(i);
                if (c >= '\u4e00' && c <= '\u9fff') {
                    String bigram = questionLower.substring(i, i + 2);
                    if (profileLower.contains(bigram)) score += 5;
                }
            }

            // 英文单词匹配
            for (String word : questionLower.split("[\\s/.,，。？]+")) {
                if (word.length() >= 2 && profileLower.contains(word)) score += 3;
            }

            // 仓库名匹配
            if (repo.getName() != null && questionLower.contains(repo.getName().toLowerCase())) score += 20;

            scored.add(Map.entry(id, score));
        }

        scored.sort((a, b) -> b.getValue() - a.getValue());

        List<Long> result = scored.stream()
                .filter(e -> e.getValue() > 0)
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (result.isEmpty()) return readyRepoIds;

        logger.info("[仓库定位] 从 {} 个仓库中筛选出 {} 个: {}", readyRepoIds.size(), result.size(),
                scored.stream().filter(e -> e.getValue() > 0).limit(5)
                        .map(e -> e.getKey() + "(" + e.getValue() + "分)")
                        .collect(Collectors.joining(", ")));
        return result;
    }

    /** AI 提取意图（意图类型 + 搜索关键词），替代原先的纯关键词提取 */
    private IntentResult extractIntent(String question) {
        String intentPrompt = "分析用户问题的意图，严格按以下格式逐行输出（不要任何额外内容）：\n\n"
                + "意图类型说明：\n"
                + "- DEBUG：排错（含\"为什么\"、\"没生效\"、\"报错\"、\"异常\"、\"错误\"等）\n"
                + "- INTEGRATION：对接（含\"怎么调\"、\"入参\"、\"请求示例\"、\"如何使用\"等）\n"
                + "- DATA_FLOW：数据流向（含\"从哪来\"、\"怎么存\"、\"哪里写入\"、\"数据来源\"等）\n"
                + "- UNDERSTAND：功能理解（含\"是什么\"、\"做了什么\"、\"流程\"、\"逻辑\"等）\n"
                + "- CONFIG：配置查询（含\"配置\"、\"超时\"、\"参数\"、\"在哪配\"等）\n\n"
                + "输出格式（每行一个字段，冒号后面是值）：\n"
                + "INTENT_TYPE: <DEBUG|INTEGRATION|DATA_FLOW|UNDERSTAND|CONFIG>\n"
                + "INTENT_LABEL: <排错|对接指南|数据流向|功能理解|配置查询>\n"
                + "SUMMARY: <一句话，描述用户想知道什么>\n"
                + "ENTITY: <核心业务实体，如：签到积分、用户登录>\n"
                + "FOCUS: <搜索重点，逗号分隔，2-4个，如：异常处理,条件判断>\n"
                + "KEYWORDS: <搜索关键词，逗号分隔，5-10个，中英文都要，包含可能的URL片段和类名片段>\n"
                + "CONFIDENCE: <0-100，对意图理解的置信度，问题模糊时给低分>\n\n"
                + "用户问题：" + question;
        try {
            String result = claudeClient.chat(intentPrompt, List.of(Map.of("role", "user", "content", "请分析意图")));
            IntentResult parsed = parseIntentResult(result, question);
            logger.info("[意图分析] type={}, confidence={}, keywords={}", parsed.intentType(), parsed.confidence(), parsed.keywords());
            return parsed;
        } catch (Exception e) {
            logger.warn("[意图分析] 失败，使用降级策略", e);
            return buildFallbackIntent(question);
        }
    }

    private IntentResult parseIntentResult(String text, String originalQuestion) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toUpperCase();
                String value = line.substring(colon + 1).trim();
                fields.put(key, value);
            }
        }
        String intentType = fields.getOrDefault("INTENT_TYPE", "UNDERSTAND");
        String intentLabel = fields.getOrDefault("INTENT_LABEL", "功能理解");
        String summary = fields.getOrDefault("SUMMARY", originalQuestion);
        String entity = fields.getOrDefault("ENTITY", "");
        List<String> focusOn = splitComma(fields.getOrDefault("FOCUS", ""));
        List<String> keywords = splitComma(fields.getOrDefault("KEYWORDS", ""));
        if (keywords.isEmpty()) keywords = extractKeywordsFallback(originalQuestion);
        int confidence = 70;
        try { confidence = Integer.parseInt(fields.getOrDefault("CONFIDENCE", "70")); } catch (NumberFormatException ignored) {}
        return new IntentResult(intentType, intentLabel, summary, entity, focusOn, keywords, confidence);
    }

    /** 根据用户确认/修改的意图，重新提炼关键词 */
    private IntentResult refineIntent(String question, IntentConfirmation confirmed) {
        String effectiveQuestion = (confirmed.clarification() != null && !confirmed.clarification().isBlank())
                ? confirmed.clarification() : question;
        try {
            IntentResult base = extractIntent(effectiveQuestion);
            // 意图类型以用户确认为准，置信度设为100
            return new IntentResult(confirmed.intentType(), confirmed.intentLabel(),
                    base.summary(), base.entity(), base.focusOn(), base.keywords(), 100);
        } catch (Exception e) {
            return new IntentResult(confirmed.intentType(), confirmed.intentLabel(),
                    effectiveQuestion, "", List.of(), extractKeywordsFallback(effectiveQuestion), 100);
        }
    }

    private List<String> splitComma(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        return java.util.Arrays.stream(value.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    /** 关键词提取降级：中文二字分词 + 原始问题 */
    private List<String> extractKeywordsFallback(String question) {
        List<String> fallback = new ArrayList<>();
        fallback.add(question.toLowerCase());
        for (int i = 0; i < question.length() - 1; i++) {
            if (question.charAt(i) >= '\u4e00' && question.charAt(i) <= '\u9fff') {
                fallback.add(question.substring(i, Math.min(i + 2, question.length())));
            }
        }
        return fallback;
    }

    private IntentResult buildFallbackIntent(String question) {
        return new IntentResult("UNDERSTAND", "功能理解", question, "",
                List.of(), extractKeywordsFallback(question), 50);
    }

    /**
     * 语义检索：优先用向量召回，Qdrant 不可用时降级到关键词粗筛。
     * 向量分数映射到 0-100，与原有 ScoredEndpoint.score 语义一致。
     */
    private List<ScoredEndpoint> semanticSearch(List<RepoEndpoints> repoEndpointsList,
                                                  String question, List<String> keywords, int maxResults) {
        // 1. 尝试向量召回
        if (vectorStoreService.isAvailable()) {
            try {
                float[] queryVector = embeddingService.embed(question);
                if (queryVector.length > 0) {
                    List<Long> repoIds = repoEndpointsList.stream()
                            .map(re -> re.repoId).toList();
                    List<VectorStoreService.SearchResult> vectorResults =
                            vectorStoreService.search(queryVector, repoIds, maxResults);

                    if (!vectorResults.isEmpty()) {
                        // 构建 fullMethod → ApiEndpointEntity 快速查找 map
                        Map<String, ApiEndpointEntity> endpointMap = new HashMap<>();
                        Map<String, RepoEndpoints> repoByMethod = new HashMap<>();
                        for (RepoEndpoints re : repoEndpointsList) {
                            for (ApiEndpointEntity ep : re.endpoints) {
                                endpointMap.put(ep.getFullMethod(), ep);
                                repoByMethod.put(ep.getFullMethod(), re);
                            }
                        }

                        List<ScoredEndpoint> results = new ArrayList<>();
                        for (VectorStoreService.SearchResult vr : vectorResults) {
                            // 优先匹配 api_endpoints（接口入口，高权重）
                            ApiEndpointEntity ep = endpointMap.get(vr.fullMethod());
                            if (ep != null) {
                                RepoEndpoints re = repoByMethod.get(vr.fullMethod());
                                int score = (int) (vr.score() * 100);
                                results.add(new ScoredEndpoint(ep, vr.repoId(),
                                        re != null ? re.repoName : "", score, vr.summary()));
                            } else {
                                // 非接口方法也保留（AI 通过 getCallers 追溯到入口）
                                // 构建虚拟 ApiEndpointEntity
                                ApiEndpointEntity fake = new ApiEndpointEntity();
                                fake.setFullMethod(vr.fullMethod());
                                fake.setEndpointType("METHOD");
                                int colonIdx = vr.fullMethod().lastIndexOf(':');
                                if (colonIdx > 0) fake.setClassName(vr.fullMethod().substring(0, colonIdx));
                                int score = (int) (vr.score() * 80); // 非接口方法稍低权重
                                results.add(new ScoredEndpoint(fake, vr.repoId(),
                                        "", score, vr.summary()));
                            }
                        }
                        if (!results.isEmpty()) {
                            long epCount = results.stream().filter(r -> !"METHOD".equals(r.endpoint.getEndpointType())).count();
                            logger.info("[语义检索] 向量召回成功: {} 个结果（含 {} 个接口入口）", results.size(), epCount);
                            return results;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("[语义检索] 向量召回异常，降级到关键词搜索: {}", e.getMessage());
            }
        }

        // 2. 降级：原有关键词粗筛
        logger.info("[语义检索] 向量不可用，降级到关键词搜索");
        return keywordSearch(repoEndpointsList, keywords, maxResults);
    }

    /** 关键词粗筛：分层评分，URL/类名精确匹配权重远高于 summary 模糊匹配 */
    private List<ScoredEndpoint> keywordSearch(List<RepoEndpoints> repoEndpointsList, List<String> keywords, int maxResults) {
        List<ScoredEndpoint> results = new ArrayList<>();
        // 过滤掉长度 <= 1 的关键词（单字中文二字分词碎片），避免误匹配
        List<String> effectiveKeywords = keywords.stream()
                .filter(kw -> kw.length() > 1)
                .collect(Collectors.toList());
        if (effectiveKeywords.isEmpty()) effectiveKeywords = keywords;

        for (RepoEndpoints re : repoEndpointsList) {
            for (ApiEndpointEntity ep : re.endpoints) {
                String urlPath = ep.getUrlPath() != null ? ep.getUrlPath().toLowerCase() : "";
                String className = ep.getClassName() != null ? ep.getClassName().toLowerCase() : "";
                String shortClass = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
                String methodName = ep.getFullMethod().toLowerCase();
                String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");

                int score = 0;
                for (String kw : effectiveKeywords) {
                    // 第一层：URL 路径精确包含（最高权重）
                    if (!urlPath.isEmpty() && urlPath.contains(kw)) {
                        score += 50;
                    }
                    // 第二层：类名精确包含（高权重）
                    if (shortClass.contains(kw)) {
                        score += 30;
                    }
                    // 第三层：方法名包含（中权重）
                    if (methodName.contains(kw)) {
                        score += 20;
                    }
                    // 第四层：call_summary 包含（低权重，且要求关键词长度 >= 3 避免碎片匹配）
                    if (kw.length() >= 3 && summary.contains(kw)) {
                        score += 5;
                    }
                }
                if (score > 0) {
                    results.add(new ScoredEndpoint(ep, re.repoId, re.repoName, score, summary));
                }
            }
        }
        results.sort((a, b) -> b.score - a.score);
        return results.stream().limit(maxResults).collect(Collectors.toList());
    }

    /**
     * AI rerank 兜底：把所有接口的一行摘要给 AI，让 AI 选出最相关的
     * 每个接口压缩成一行："序号. [TYPE] METHOD URL - ClassName | 摘要片段"
     */
    private List<ScoredEndpoint> aiRerank(List<RepoEndpoints> repoEndpointsList, String question, List<String> keywords) {
        // 构建全量接口摘要列表
        List<ScoredEndpoint> allEndpoints = new ArrayList<>();
        StringBuilder catalog = new StringBuilder();
        int idx = 0;
        for (RepoEndpoints re : repoEndpointsList) {
            for (ApiEndpointEntity ep : re.endpoints) {
                String shortClass = ep.getClassName() != null && ep.getClassName().contains(".")
                        ? ep.getClassName().substring(ep.getClassName().lastIndexOf('.') + 1)
                        : (ep.getClassName() != null ? ep.getClassName() : "");
                String summary = re.summaries.getOrDefault(ep.getFullMethod(), "");
                String summarySnippet = summary.length() > 60 ? summary.substring(0, 60) : summary;

                catalog.append(idx).append(". ");
                if (ep.getHttpMethod() != null) catalog.append(ep.getHttpMethod()).append(" ");
                catalog.append(ep.getUrlPath() != null ? ep.getUrlPath() : "");
                catalog.append(" - ").append(shortClass);
                if (!summarySnippet.isBlank()) catalog.append(" | ").append(summarySnippet);
                catalog.append("\n");

                allEndpoints.add(new ScoredEndpoint(ep, re.repoId, re.repoName, 0, summary));
                idx++;
            }
        }

        if (allEndpoints.isEmpty()) return List.of();


        String rerankPrompt = "以下是一个 Java 项目的所有接口列表。用户的问题是：\"" + question + "\"\n\n"
                + "请从列表中选出与用户问题相关的所有接口（通常 3-8 个），只输出序号，逗号分隔，不要其他内容。\n"
                + "注意：同一业务的不同操作（如创建、查询、更新）都应该选上。不同 URL 前缀但属于同一业务的也要选。\n"
                + "如果没有任何相关接口，输出 NONE\n\n"
                + catalog;

        try {
            String result = claudeClient.chat(rerankPrompt, List.of(Map.of("role", "user", "content", "请选择相关接口")));
            logger.info("[智能问答] AI rerank 结果: {}", result.trim());

            if (result.trim().equalsIgnoreCase("NONE")) return List.of();

            List<ScoredEndpoint> selected = new ArrayList<>();
            for (String part : result.replaceAll("[^0-9,]", "").split(",")) {
                try {
                    int i = Integer.parseInt(part.trim());
                    if (i >= 0 && i < allEndpoints.size()) {
                        ScoredEndpoint se = allEndpoints.get(i);
                        selected.add(new ScoredEndpoint(se.endpoint, se.repoId, se.repoName, 50, se.summary));
                    }
                } catch (NumberFormatException ignored) {}
            }
            return selected;
        } catch (Exception e) {
            logger.warn("[智能问答] AI rerank 失败", e);
            return List.of();
        }
    }

    /**
     * 生成模块概览回答：当候选接口较多时，不加载完整源码，
     * 而是给 AI 一个接口清单 + 每个接口的方法签名/注解/一句话描述，
     * 让 AI 生成：1.接口概览 2.对接流程 3.关键注意事项
     */
    private SmartQAResponse generateOverviewAnswer(List<ScoredEndpoint> candidates, String question, IntentResult intent) {
        StringBuilder contextForAI = new StringBuilder();
        contextForAI.append("以下是该业务模块的所有相关接口清单：\n\n");

        List<String> references = new ArrayList<>();
        int idx = 0;
        for (ScoredEndpoint se : candidates) {
            idx++;
            String shortClass = se.endpoint.getClassName() != null
                    ? se.endpoint.getClassName().substring(se.endpoint.getClassName().lastIndexOf('.') + 1) : "";
            contextForAI.append(idx).append(". ");
            if (se.endpoint.getHttpMethod() != null) contextForAI.append("[").append(se.endpoint.getHttpMethod()).append("] ");
            contextForAI.append(se.endpoint.getUrlPath() != null ? se.endpoint.getUrlPath() : "");
            contextForAI.append(" — ").append(shortClass);

            // 加载方法签名和入参类型（轻量信息，不加载完整源码）
            try {
                var detail = callGraphEngine.getMethodSourceDetail(se.repoId, se.endpoint.getFullMethod(), null);
                if (detail != null) {
                    if (detail.methodSignature() != null) {
                        contextForAI.append("\n   签名: ").append(detail.methodSignature());
                    }
                    if (detail.paramClasses() != null && !detail.paramClasses().isEmpty()) {
                        for (var pc : detail.paramClasses()) {
                            contextForAI.append("\n   入参: ").append(pc.shortName());
                            // 只列前 5 个字段
                            int fieldCount = 0;
                            for (String field : pc.fields()) {
                                if (fieldCount++ >= 5) { contextForAI.append("\n         ..."); break; }
                                contextForAI.append("\n         ").append(field);
                            }
                        }
                    }
                }
            } catch (Exception e) { /* skip */ }

            // 加载 call_summary 中的业务描述
            if (se.summary != null && !se.summary.isBlank()) {
                String snippet = se.summary.length() > 100 ? se.summary.substring(0, 100) : se.summary;
                contextForAI.append("\n   摘要: ").append(snippet);
            }

            contextForAI.append("\n\n");
            references.add(se.endpoint.getFullMethod());
        }

        String overviewPrompt = "你是一个企业级 Java 项目的代码分析助手。\n\n"
                + "## 任务\n"
                + "用户问：\"" + question + "\"\n\n"
                + "请基于以下接口清单，按照这个结构回答：\n\n"
                + "### 1. 模块概览\n"
                + "一句话说明这个模块是做什么的，列出所有接口及其功能（表格形式：接口 | HTTP方法 | 功能说明）\n\n"
                + "### 2. 对接流程\n"
                + "说明正确的对接顺序（哪个接口先调，哪个后调，有什么依赖关系）\n\n"
                + "### 3. 关键注意事项\n"
                + "基于接口签名和入参信息，列出对接时需要注意的要点（必填字段、数据格式、调用顺序等）\n\n"
                + "### 4. 想深入了解？\n"
                + "列出用户可能想追问的方向（如\"某个具体接口的详细入参\"、\"错误码说明\"等）\n\n"
                + "## 接口清单\n\n"
                + contextForAI;

        String answer = claudeClient.chat(overviewPrompt, List.of(Map.of("role", "user", "content", question)));

        List<MatchedEndpoint> matchedEndpoints = candidates.stream().map(this::toMatchedEndpoint).collect(Collectors.toList());
        return new SmartQAResponse(answer, references, false, intent.keywords(), matchedEndpoints, false, null, false);
    }

    /** 加载代码上下文，调 AI 生成最终回答（function calling loop 模式） */
    private SmartQAResponse generateAnswer(List<ScoredEndpoint> allResults, String question,
                                            List<String> keywords, List<MatchedEndpoint> matchedEndpoints) {
        return generateAnswerWithLoop(allResults, question, keywords, matchedEndpoints, null);
    }

    /**
     * Claude function calling loop 核心实现。
     * LLM 自主决定每轮调用哪些工具读取源码/调用链，直到判断信息足够（stop_reason=end_turn）。
     * 轮数由调用链深度动态决定，安全阀为 MAX_NODES 个已访问方法节点。
     *
     * @param stepCallback 每次工具调用时的回调（用于 SSE 推送），可为 null
     */
    public SmartQAResponse generateAnswerWithLoop(List<ScoredEndpoint> allResults, String question,
                                                   List<String> keywords, List<MatchedEndpoint> matchedEndpoints,
                                                   java.util.function.Consumer<ToolCallStep> stepCallback) {
        return generateAnswerWithLoop(allResults, question, keywords, matchedEndpoints, stepCallback, null);
    }

    /**
     * 同上，额外支持「问题理解」回调：进入工具循环前先做一次意图分析并回调（用于 SSE 展示）。
     * @param intentCallback 问题理解回调（可为 null）
     */
    public SmartQAResponse generateAnswerWithLoop(List<ScoredEndpoint> allResults, String question,
                                                   List<String> keywords, List<MatchedEndpoint> matchedEndpoints,
                                                   java.util.function.Consumer<ToolCallStep> stepCallback,
                                                   java.util.function.Consumer<IntentUnderstanding> intentCallback) {
        return generateAnswerWithLoop(allResults, question, keywords, matchedEndpoints, stepCallback, intentCallback, null);
    }

    /**
     * 同上，额外支持「最终答案流式」回调：判定要出答案时改用流式生成，逐 token 回调（用于打字机效果）。
     * @param tokenCallback 最终答案 token 回调（可为 null，为 null 时保持一次性返回）
     */
    public SmartQAResponse generateAnswerWithLoop(List<ScoredEndpoint> allResults, String question,
                                                   List<String> keywords, List<MatchedEndpoint> matchedEndpoints,
                                                   java.util.function.Consumer<ToolCallStep> stepCallback,
                                                   java.util.function.Consumer<IntentUnderstanding> intentCallback,
                                                   java.util.function.Consumer<String> tokenCallback) {
        // 安全阀：最多访问 50 个不同方法节点，防止超大项目无限展开
        final int MAX_NODES = 50;
        final int MAX_ROUNDS = 25; // 最大轮数限制，新增工具后需要更多轮次收集完整业务信息
        Set<String> visitedMethods = new HashSet<>();
        Set<String> calledTools = new HashSet<>();  // 非 getMethodSource 工具去重：toolName|fullMethod
        List<String> references = new ArrayList<>();
        List<ToolCallStep> steps = new ArrayList<>();

        // 确定 repoId（取第一个结果）
        Long repoId = allResults.isEmpty() ? null : allResults.get(0).repoId;

        // 获取技术栈和自定义 prompt
        String techStack = "";
        if (repoId != null) {
            var repo = repositoryRepo.findById(repoId).orElse(null);
            if (repo != null) {
                techStack = projectInfoExtractor.generateTechStackDescription(
                        projectInfoExtractor.extract(repo.getLocalPath()));
            }
        }
        // 构建 system prompt：基础人设（可配置）+ 技术栈 + 工具调用规则（可配置）
        String systemPrompt = promptService.get("qa.system")
                + "\n\n" + techStack
                + "\n\n" + promptService.get("qa.tool_rules");

        // 构建初始入参：接口摘要列表
        StringBuilder initialContext = new StringBuilder();
        initialContext.append("以下是与问题相关的入口接口，请逐步分析：\n\n");
        for (ScoredEndpoint se : allResults) {
            String shortCls = se.endpoint.getClassName() != null
                    ? se.endpoint.getClassName().substring(se.endpoint.getClassName().lastIndexOf('.') + 1) : "";
            initialContext.append("- [").append(se.endpoint.getEndpointType()).append("] ");
            if (se.endpoint.getHttpMethod() != null) initialContext.append(se.endpoint.getHttpMethod()).append(" ");
            if (se.endpoint.getUrlPath() != null) initialContext.append(se.endpoint.getUrlPath()).append(" ");
            initialContext.append("→ ").append(shortCls);
            initialContext.append("\n  完整方法: ").append(se.endpoint.getFullMethod()).append("\n");
            references.add(se.endpoint.getFullMethod());
        }

        // ── 问题理解（语义转换）：进循环前先做一次意图分析，回调给前端展示，并注入循环上下文 ──
        String understandingBlock = "";
        try {
            IntentResult intent = extractIntent(question);
            if (intentCallback != null) {
                intentCallback.accept(new IntentUnderstanding(
                        intent.intentLabel(), intent.summary(), intent.focusOn()));
            }
            StringBuilder ub = new StringBuilder();
            ub.append("\n\n## 对用户问题的理解（请据此组织回答）\n");
            ub.append("- 意图类型: ").append(intent.intentLabel()).append("\n");
            if (intent.summary() != null && !intent.summary().isBlank()) {
                ub.append("- 用户真正想知道: ").append(intent.summary()).append("\n");
            }
            if (intent.focusOn() != null && !intent.focusOn().isEmpty()) {
                ub.append("- 必须覆盖的角度: ").append(String.join("、", intent.focusOn())).append("\n");
            }
            understandingBlock = ub.toString();
        } catch (Exception e) {
            logger.warn("[ToolLoop] 问题理解失败，跳过该步", e);
        }

        // messages history（含 tool_result）
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content",
                question + understandingBlock + "\n\n" + initialContext));

        // 工具定义
        List<ClaudeApiClient.ToolDefinition> tools = CodeAnalysisToolExecutor.buildToolDefinitions();

        // ── Function calling loop ──────────────────────────────────────────────
        String finalAnswer = null;
        int round = 0;

        while (true) {
            round++;
            logger.info("[ToolLoop] Round {} 开始，已访问方法数={}", round, visitedMethods.size());

            ClaudeApiClient.ToolCallResponse response;
            try {
                response = claudeClient.chatWithTools(systemPrompt, messages, tools);
            } catch (Exception e) {
                logger.error("[ToolLoop] Claude 调用失败 round={}: {}", round, e.getMessage());
                finalAnswer = "❌ AI 分析中断（" + e.getMessage() + "）\n\n已访问方法：\n"
                        + visitedMethods.stream().map(m -> "- " + m).collect(Collectors.joining("\n"));
                break;
            }

            // 把 assistant 这一轮的回复加入 messages（保留完整 content array）
            List<Map<String, Object>> assistantContent = new ArrayList<>();
            if (response.rawContent() != null && response.rawContent().isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode block : response.rawContent()) {
                    assistantContent.add(mapper.convertValue(block, Map.class));
                }
            }
            messages.add(Map.of("role", "assistant", "content", assistantContent));

            // 判断是否结束
            if (!response.hasToolUse()) {
                if (tokenCallback != null) {
                    // 流式重新生成最终答案（打字机）：保留 assistant 文本块，追加“输出最终答案”指令后流式生成
                    messages.add(Map.of("role", "user", "content",
                            "请基于以上分析，输出面向开发者的最终答案（遵循 system prompt 的格式与收尾铁律），不要再调用工具。"));
                    logger.info("[ToolLoop] 最终答案流式生成开始");
                    try {
                        finalAnswer = claudeClient.chatStream(systemPrompt, toPlainMessages(messages), tokenCallback);
                    } catch (Exception e) {
                        logger.error("[ToolLoop] 流式生成失败，回退一次性文本: {}", e.getMessage());
                        finalAnswer = response.text();
                    }
                } else {
                    finalAnswer = response.text();
                }
                break;
            }

            // 阶段性推理：模型本轮在调用工具前给出的思考文本，展示“得出了哪些结论/下一步打算”
            if (stepCallback != null && response.text() != null && !response.text().isBlank()) {
                stepCallback.accept(new ToolCallStep("reasoning", null,
                        "💭 推理", round, truncate(response.text(), 300)));
            }

            // 安全阀检查：轮数上限
            if (round > MAX_ROUNDS) {
                logger.warn("[ToolLoop] 轮数上限({}), 要求 AI 输出最终答案", MAX_ROUNDS);
                List<Map<String, Object>> roundLimitResults = new ArrayList<>();
                roundLimitResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", response.toolUses().get(0).id(),
                        "content", "ROUND_LIMIT_REACHED: 已执行 " + MAX_ROUNDS + " 轮工具调用，请基于已获取的信息输出最终答案，不要再调用工具"
                ));
                messages.add(Map.of("role", "user", "content", roundLimitResults));
                continue;
            }

            // 安全阀检查：方法节点数
            if (visitedMethods.size() >= MAX_NODES) {
                logger.warn("[ToolLoop] 安全阀触发，已访问 {} 个方法", visitedMethods.size());
                // 追加安全阀说明后再让 LLM 输出最终答案
                List<Map<String, Object>> safetyResults = new ArrayList<>();
                safetyResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", response.toolUses().get(0).id(),
                        "content", "SAFETY_LIMIT_REACHED: 已分析 " + MAX_NODES + " 个方法节点，请基于现有信息输出最终答案"
                ));
                messages.add(Map.of("role", "user", "content", safetyResults));
                continue;
            }

            // 执行所有工具调用，收集结果
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ClaudeApiClient.ToolUseBlock toolUse : response.toolUses()) {
                String fullMethod = toolUse.input().path("fullMethod").asText(null);

                // 去重：同一方法不重复读
                if ("getMethodSource".equals(toolUse.name()) && fullMethod != null) {
                    if (!visitedMethods.add(fullMethod)) {
                        logger.debug("[ToolLoop] 跳过重复方法: {}", fullMethod);
                        toolResults.add(Map.of(
                                "type", "tool_result",
                                "tool_use_id", toolUse.id(),
                                "content", "ALREADY_LOADED: 该方法源码已在之前的轮次中提供"
                        ));
                        continue;
                    }
                } else if (!"getMethodSource".equals(toolUse.name())) {
                    // 其它工具（getCallees/getExceptions/getConstants/getBoundaries...）按 toolName|fullMethod 去重，避免空转
                    String toolKey = toolUse.name() + "|" + (fullMethod != null ? fullMethod : "");
                    if (!calledTools.add(toolKey)) {
                        logger.debug("[ToolLoop] 跳过重复工具调用: {}", toolKey);
                        toolResults.add(Map.of(
                                "type", "tool_result",
                                "tool_use_id", toolUse.id(),
                                "content", "ALREADY_CALLED: 该工具已对此方法调用过，结果见之前轮次，请勿重复调用"
                        ));
                        continue;
                    }
                }

                // 推送 SSE 步骤
                String stepLabel = buildStepLabel(toolUse.name(), fullMethod);
                ToolCallStep step = new ToolCallStep(toolUse.name(), fullMethod, stepLabel, round, null);
                steps.add(step);
                if (stepCallback != null) stepCallback.accept(step);
                logger.info("[ToolLoop] 执行工具: {} fullMethod={}", toolUse.name(), fullMethod);

                String result = (repoId != null)
                        ? toolExecutor.execute(repoId, toolUse.name(), toolUse.input())
                        : "REPO_NOT_FOUND";

                // 执行后推送“读到了什么”的结果摘要，让用户看到具体读取内容
                if (stepCallback != null) {
                    String readLabel = "↳ 已读取 " + (fullMethod != null ? shortMethod(fullMethod) : toolUse.name());
                    stepCallback.accept(new ToolCallStep(toolUse.name(), fullMethod, readLabel, round, previewResult(result)));
                }

                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", toolUse.id(),
                        "content", result
                ));
            }

            // 追加新轮次 tool_result 前，先把历史轮次的工具结果瘦身，避免 messages 体积随轮数线性累积
            condenseOldToolResults(messages);
            // 把所有 tool_result 追加到 messages
            messages.add(Map.of("role", "user", "content", toolResults));
        }

        logger.info("[ToolLoop] 完成，共 {} 轮，访问方法 {} 个", round, visitedMethods.size());

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = "源码分析完成，但未能提取到有效回答。请提供更多信息。";
        }

        return new SmartQAResponse(finalAnswer, references, false, keywords, matchedEndpoints, false, null, false);
    }

    private String buildStepLabel(String toolName, String fullMethod) {
        String shortRef = fullMethod != null ? shortMethod(fullMethod) : "";
        return switch (toolName) {
            case "getMethodSource" -> "🔍 正在分析 " + shortRef + "...";
            case "getCallees"      -> "📋 获取 " + shortRef + " 的调用列表...";
            case "getCallers"      -> "⬆️ 查找调用 " + shortRef + " 的上游方法...";
            case "getBoundaries"   -> "🔌 检查 " + shortRef + " 的外部依赖...";
            default                -> "⚙️ 执行 " + toolName + "...";
        };
    }

    /** 工具调用步骤，用于 SSE 推送（detail=读取结果摘要/阶段推理，可为 null） */
    public record ToolCallStep(String toolName, String fullMethod, String label, int round, String detail) {}


    /**
     * 把 messages 历史中除最近一轮之外的所有 tool_result 内容替换为轻量占位符。
     * Claude 已在当轮推理中消化过这些内容，不需要再重复携带原始源码文本，
     * 从而避免 messages 体积随工具调用轮数线性累积，大幅减少每次请求的 input token。
     */
    @SuppressWarnings("unchecked")
    private void condenseOldToolResults(List<Map<String, Object>> messages) {
        // 找到最后一条 tool_result 消息的索引，保留它，其余的压缩
        int lastToolResultIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object content = messages.get(i).get("content");
            if (content instanceof List) {
                boolean isToolResult = ((List<?>) content).stream()
                        .anyMatch(b -> b instanceof Map && "tool_result".equals(((Map<?, ?>) b).get("type")));
                if (isToolResult) { lastToolResultIdx = i; break; }
            }
        }
        if (lastToolResultIdx <= 0) return;

        // 把 lastToolResultIdx 之前所有 tool_result 的 content 字段替换为已读标记
        for (int i = 0; i < lastToolResultIdx; i++) {
            Object content = messages.get(i).get("content");
            if (!(content instanceof List)) continue;
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
            boolean isToolResult = blocks.stream().anyMatch(b -> "tool_result".equals(b.get("type")));
            if (!isToolResult) continue;

            List<Map<String, Object>> condensed = new ArrayList<>();
            for (Map<String, Object> block : blocks) {
                if ("tool_result".equals(block.get("type"))) {
                    condensed.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", block.getOrDefault("tool_use_id", ""),
                            "content", "CONDENSED: 已在前序轮次分析，结论已纳入推理上下文"
                    ));
                } else {
                    condensed.add(block);
                }
            }
            messages.set(i, Map.of("role", "user", "content", condensed));
        }
    }

    /** 把工具结果压成一行摘要，便于在思考面板展示“读到了什么”（≤200 字） */
    private String previewResult(String result) {
        if (result == null || result.isBlank()) return "";
        String oneLine = result.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    /** 截断文本到指定长度 */
    private String truncate(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 问题理解（语义转换结果），用于 SSE 展示在思考面板顶部 */
    public record IntentUnderstanding(String intentLabel, String summary, List<String> focus) {}

    /** 把工具循环的富消息（content 可能是 String 或 block 数组）拍平成 chatStream 需要的 {role, content(String)} 形式 */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> toPlainMessages(List<Map<String, Object>> messages) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.get("role"));
            Object content = msg.get("content");
            StringBuilder sb = new StringBuilder();
            if (content instanceof String s) {
                sb.append(s);
            } else if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (!(b instanceof Map)) { sb.append(String.valueOf(b)).append("\n"); continue; }
                    Map<String, Object> block = (Map<String, Object>) b;
                    String type = String.valueOf(block.get("type"));
                    switch (type) {
                        case "text" -> sb.append(String.valueOf(block.getOrDefault("text", "")));
                        case "tool_use" -> sb.append("[调用工具 ").append(block.get("name"))
                                .append(" 参数=").append(block.get("input")).append("]");
                        case "tool_result" -> sb.append(String.valueOf(block.getOrDefault("content", "")));
                        default -> { /* 忽略未知块 */ }
                    }
                    sb.append("\n");
                }
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) text = "(无内容)";
            out.add(Map.of("role", role, "content", text));
        }
        return out;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private MatchedEndpoint toMatchedEndpoint(ScoredEndpoint se) {
        String shortClass = se.endpoint.getClassName() != null && se.endpoint.getClassName().contains(".")
                ? se.endpoint.getClassName().substring(se.endpoint.getClassName().lastIndexOf('.') + 1)
                : (se.endpoint.getClassName() != null ? se.endpoint.getClassName() : "");
        String methodName = se.endpoint.getFullMethod().contains(":")
                ? se.endpoint.getFullMethod().substring(se.endpoint.getFullMethod().lastIndexOf(':') + 1)
                : se.endpoint.getFullMethod();
        int paren = methodName.indexOf('(');
        if (paren > 0) methodName = methodName.substring(0, paren);
        return new MatchedEndpoint(se.endpoint.getFullMethod(), shortClass, methodName,
                se.endpoint.getEndpointType(), se.endpoint.getHttpMethod(),
                se.endpoint.getUrlPath(), se.repoName, se.score);
    }

    private record RepoEndpoints(Long repoId, String repoName, List<ApiEndpointEntity> endpoints, Map<String, String> summaries) {}

    /** 从粗筛结果构建候选池 RepoEndpoints（只包含命中的接口，用于缩小 AI rerank 范围） */
    private List<RepoEndpoints> buildCandidatePool(List<RepoEndpoints> allRepos, List<ScoredEndpoint> candidates) {
        // 按 repoId 分组候选接口
        Map<Long, List<ScoredEndpoint>> byRepo = new HashMap<>();
        for (ScoredEndpoint se : candidates) {
            byRepo.computeIfAbsent(se.repoId, k -> new ArrayList<>()).add(se);
        }
        List<RepoEndpoints> pool = new ArrayList<>();
        for (Map.Entry<Long, List<ScoredEndpoint>> entry : byRepo.entrySet()) {
            Long repoId = entry.getKey();
            List<ScoredEndpoint> repoSes = entry.getValue();
            // 找到对应的原始 RepoEndpoints 获取 summaries
            RepoEndpoints original = allRepos.stream().filter(r -> r.repoId.equals(repoId)).findFirst().orElse(null);
            if (original == null) continue;
            List<ApiEndpointEntity> eps = repoSes.stream().map(se -> se.endpoint).collect(Collectors.toList());
            pool.add(new RepoEndpoints(repoId, original.repoName, eps, original.summaries));
        }
        return pool;
    }

    /** 提取 URL 中的业务关键词段（如 /backend/goods/create → ["backend", "goods"]） */
    private Set<String> extractUrlSegments(String urlPath) {
        Set<String> segments = new HashSet<>();
        if (urlPath == null || urlPath.length() < 2) return segments;
        for (String seg : urlPath.split("/")) {
            if (seg.length() >= 2 && !seg.startsWith("{")) {
                segments.add(seg.toLowerCase());
            }
        }
        return segments;
    }

    /** 过滤出有效的扩展搜索关键词（长度>=3，排除通用词） */
    private List<String> effectiveKeywordsForExpand(List<String> keywords) {
        Set<String> stopWords = Set.of("api", "接口", "对接", "调用", "参数", "入参", "请求", "响应",
                "注意", "事项", "注意事项", "怎么", "如何", "什么", "哪些", "需要");
        return keywords.stream()
                .filter(kw -> kw.length() >= 2)
                .filter(kw -> !stopWords.contains(kw))
                .collect(Collectors.toList());
    }

    public record ScoredEndpoint(ApiEndpointEntity endpoint, Long repoId, String repoName, int score, String summary) {}

    /**
     * 预设问题（不调 AI，直接用已有数据生成）
     */
    public String presetAnswer(Long repoId, String fullMethod, String presetType) {
        switch (presetType) {
            case "what":
                return codeGenerator.generate(repoId, fullMethod);
            case "risk":
                return generateRiskReport(repoId, fullMethod);
            case "db":
                return generateDbReport(repoId, fullMethod);
            case "external":
                return generateExternalReport(repoId, fullMethod);
            default:
                return "未知的预设问题类型";
        }
    }

    // 会话摘要缓存: repoId + methods hash → 摘要文本（带 TTL 30min）
    private final Map<String, CachedSummary> conversationSummaryCache = new ConcurrentHashMap<>();

    /**
     * 自由问答（调用 Claude）
     */
    public QAResponse ask(Long repoId, List<String> selectedMethods, String question, List<Map<String, String>> history) {
        // 检查缓存（含 TTL 校验）
        String cacheKey = repoId + "|" + String.join(",", selectedMethods) + "|" + question.hashCode();
        CachedAnswer cached = answerCache.get(cacheKey);
        if (cached != null && history.isEmpty() && !cached.isExpired()) {
            return new QAResponse(cached.answer, cached.references, true);
        }

        // ========== 每次都重新读取最新源码 ==========
        StringBuilder context = new StringBuilder();
        List<String> references = new ArrayList<>();

        for (String method : selectedMethods) {
            context.append("=== 接口: ").append(shortMethod(method)).append(" ===\n");

            // 加载调用链上每个方法的完整源码（深度限制 5 层，避免大项目无限展开）
            try {
                var tree = callGraphEngine.expandCallTree(repoId, method, 5, true);
                if (tree.root() != null) {
                    List<String> chainMethods = new ArrayList<>();
                    collectMethods(tree.root(), chainMethods, new HashSet<>());
                    for (String m : chainMethods) {
                        String src = callGraphEngine.getMethodSource(repoId, m);
                        if (src != null) {
                            context.append("// --- ").append(shortMethod(m)).append(" ---\n");
                            context.append(src).append("\n\n");
                        }
                    }
                }
            } catch (Exception e) {
                String pseudoCode = codeGenerator.generate(repoId, method);
                context.append(pseudoCode).append("\n\n");
            }

            // 入参实体类字段和校验注解
            var sourceDetail = callGraphEngine.getMethodSourceDetail(repoId, method, method);
            if (sourceDetail != null) {
                if (sourceDetail.methodSignature() != null) {
                    context.append("方法签名: ").append(sourceDetail.methodSignature()).append("\n");
                }
                if (sourceDetail.paramClasses() != null && !sourceDetail.paramClasses().isEmpty()) {
                    context.append("入参实体类:\n");
                    for (var pc : sourceDetail.paramClasses()) {
                        context.append("  ").append(pc.shortName()).append(":\n");
                        for (String field : pc.fields()) {
                            context.append("    ").append(field).append("\n");
                        }
                    }
                    context.append("\n");
                }
                if (sourceDetail.enumValues() != null && !sourceDetail.enumValues().isEmpty()) {
                    context.append("枚举值:\n");
                    for (String ev : sourceDetail.enumValues()) {
                        context.append("  ").append(ev).append("\n");
                    }
                    context.append("\n");
                }
            }

            // 边界点
            List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, method);
            if (!boundaries.isEmpty()) {
                context.append("边界点:\n");
                for (BoundaryEntity b : boundaries) {
                    context.append("- [").append(b.getBoundaryType()).append("] ").append(b.getContext() != null ? b.getContext().split("\n")[0] : "").append("\n");
                }
                context.append("\n");
            }

            references.add(method);
        }

        // 获取项目技术栈信息
        String techStack = "";
        var repoEntity = repositoryRepo.findById(repoId).orElse(null);
        if (repoEntity != null) {
            var projectInfo = projectInfoExtractor.extract(repoEntity.getLocalPath());
            techStack = projectInfoExtractor.generateTechStackDescription(projectInfo);
        }

        // ========== 构建 system prompt: 角色 + 技术栈 + 源码 + 会话摘要 ==========
        String basePrompt = promptService.get("qa.system");

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append(basePrompt).append("\n\n");
        systemPrompt.append(techStack).append("\n\n");
        systemPrompt.append("## 以下是用户选择的接口调用链代码：\n\n").append(context);

        // 追问时加入会话摘要
        String summaryKey = repoId + "|" + String.join(",", selectedMethods);
        if (!history.isEmpty()) {
            CachedSummary existingSummary = conversationSummaryCache.get(summaryKey);
            if (existingSummary != null && !existingSummary.isExpired()) {
                systemPrompt.append("\n## 之前的对话摘要\n").append(existingSummary.text).append("\n");
            }
        }

        // 构建消息：只保留最近 2 轮完整对话 + 新问题
        List<Map<String, String>> messages = new ArrayList<>();
        if (history.size() > 4) {
            messages.addAll(history.subList(history.size() - 4, history.size()));
        } else {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", question));

        String answer = claudeClient.chat(systemPrompt.toString(), messages);

        // ========== 生成会话摘要（异步，不阻塞回答） ==========
        final String finalQuestion = question;
        final String finalAnswer = answer;
        final String finalSummaryKey = summaryKey;
        if (!history.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                try {
                    String oldSummary = conversationSummaryCache.containsKey(finalSummaryKey)
                            && !conversationSummaryCache.get(finalSummaryKey).isExpired()
                            ? conversationSummaryCache.get(finalSummaryKey).text : "";
                    String summaryPrompt = "以下是一次关于 Java 项目代码的问答对话。请生成一段精简的对话摘要。\n\n"
                            + "摘要要求：\n"
                            + "1. 保留用户的问题意图（用户想知道什么）\n"
                            + "2. 保留涉及的具体类名.方法名（如 UcontentSyncListener.saveCourseInfo）\n"
                            + "3. 保留关键结论（如\"当 language==0 时代码会设为'无'\"）\n"
                            + "4. 保留待确认的点（如\"需要确认上游发送方的逻辑\"）\n"
                            + "5. 不要包含源码内容\n"
                            + "6. 控制在 3-5 句话以内\n\n"
                            + (oldSummary.isEmpty() ? "" : "之前的摘要：" + oldSummary + "\n\n")
                            + "用户问：" + finalQuestion + "\n\n"
                            + "AI 答：" + finalAnswer;
                    String newSummary = claudeClient.chat(
                            "你是一个代码问答对话的摘要助手。只输出摘要文本，不要标题、不要列表、不要源码。",
                            List.of(Map.of("role", "user", "content", summaryPrompt)));
                    conversationSummaryCache.put(finalSummaryKey, new CachedSummary(newSummary));
                    logger.debug("会话摘要更新: {}", newSummary.substring(0, Math.min(100, newSummary.length())));
                } catch (Exception e) {
                    logger.warn("生成会话摘要失败", e);
                }
            });
        }

        // 缓存（仅无历史对话时缓存，带时间戳供 TTL 检查）
        if (history.isEmpty()) {
            answerCache.put(cacheKey, new CachedAnswer(answer, references));
        }

        // 回填摘要：从 AI 回答中提取一句话描述，保存到 chunks 表的 call_summary
        // 只在首次问答时回填（无历史对话），避免重复
        if (history.isEmpty()) {
            backfillSummary(repoId, selectedMethods, question, answer);
        }

        return new QAResponse(answer, references, false);
    }

    // backfillSummary 方法级并发锁：防止同一方法被多个请求同时回填导致覆盖
    private final ConcurrentHashMap<String, Boolean> backfillLocks = new ConcurrentHashMap<>();

    /**
     * 回填摘要：只写入从源码静态提取的信息（方法签名、注解、参数类型）
     * 严禁将用户问题文本或 AI 输出内容写回数据库，避免搜索索引污染
     */
    private void backfillSummary(Long repoId, List<String> methods, String question, String answer) {
        try {
            for (String method : methods) {
                // 方法级并发保护：同一方法同时只允许一个线程回填
                String lockKey = repoId + "|" + method;
                if (backfillLocks.putIfAbsent(lockKey, Boolean.TRUE) != null) {
                    logger.debug("回填摘要跳过（已有并发写）: {}", shortMethod(method));
                    continue;
                }
                try {
                    chunkRepo.findByRepoIdAndFullMethod(repoId, method).ifPresent(chunk -> {
                        String existing = chunk.getCallSummary();
                        // 如果已有静态摘要，不再追加任何内容
                        if (existing != null && existing.length() > 200) return;

                        // 只写入源码静态信息：方法签名 + 参数类型
                        StringBuilder staticInfo = new StringBuilder();
                        try {
                            var detail = callGraphEngine.getMethodSourceDetail(repoId, method, method);
                            if (detail != null && detail.methodSignature() != null) {
                                staticInfo.append(detail.methodSignature());
                            }
                            if (detail != null && detail.paramClasses() != null) {
                                for (var pc : detail.paramClasses()) {
                                    staticInfo.append(" ").append(pc.shortName());
                                }
                            }
                        } catch (Exception e) { /* skip */ }

                        // 注解
                        if (chunk.getAnnotations() != null && !chunk.getAnnotations().isBlank()) {
                            staticInfo.append(" ").append(chunk.getAnnotations());
                        }

                        // 只追加静态信息，不追加用户问题文本
                        String newStaticPart = staticInfo.toString().trim();
                        if (newStaticPart.isEmpty()) return;

                        if (existing != null && !existing.isBlank()) {
                            if (existing.contains(newStaticPart)) return; // 避免重复
                            chunk.setCallSummary(existing + " | " + newStaticPart);
                        } else {
                            chunk.setCallSummary(newStaticPart);
                        }

                        chunkRepo.save(chunk);
                        logger.debug("回填静态摘要: {} -> {}", shortMethod(method), newStaticPart.substring(0, Math.min(80, newStaticPart.length())));
                    });
                } finally {
                    backfillLocks.remove(lockKey);
                }
            }
        } catch (Exception e) {
            logger.warn("回填摘要失败", e);
        }
    }

    // ========== 预设报告生成 ==========

    private String generateRiskReport(Long repoId, String fullMethod) {
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, fullMethod, 5, true);
        List<String> methods = new ArrayList<>();
        collectMethods(tree.root(), methods, new HashSet<>());

        StringBuilder report = new StringBuilder();
        report.append("# 风险分析报告\n\n");

        for (String method : methods) {
            List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, method);
            List<BoundaryEntity> risks = boundaries.stream()
                    .filter(b -> "EXCEPTION".equals(b.getBoundaryType()) || "HTTP".equals(b.getBoundaryType())
                            || "GRPC".equals(b.getBoundaryType()) || "DB".equals(b.getBoundaryType()))
                    .collect(Collectors.toList());
            if (!risks.isEmpty()) {
                report.append("## ").append(shortMethod(method)).append("\n");
                for (BoundaryEntity b : risks) {
                    report.append("- [").append(b.getBoundaryType()).append("] ").append(b.getContext() != null ? b.getContext().split("\n")[0] : "").append("\n");
                }
                report.append("\n");
            }
        }
        return report.toString();
    }

    private String generateDbReport(Long repoId, String fullMethod) {
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, fullMethod, 5, true);
        List<String> methods = new ArrayList<>();
        collectMethods(tree.root(), methods, new HashSet<>());

        StringBuilder report = new StringBuilder();
        report.append("# 数据库操作报告\n\n");

        for (String method : methods) {
            List<BoundaryEntity> dbOps = boundaryRepo.findByRepoIdAndFullMethod(repoId, method).stream()
                    .filter(b -> "DB".equals(b.getBoundaryType()))
                    .collect(Collectors.toList());
            if (!dbOps.isEmpty()) {
                report.append("## ").append(shortMethod(method)).append("\n");
                for (BoundaryEntity b : dbOps) {
                    report.append("- ").append(b.getContext() != null ? b.getContext().split("\n")[0] : "").append("\n");
                }
                report.append("\n");
            }
        }
        return report.toString();
    }

    private String generateExternalReport(Long repoId, String fullMethod) {
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, fullMethod, 5, true);
        List<String> methods = new ArrayList<>();
        collectMethods(tree.root(), methods, new HashSet<>());

        StringBuilder report = new StringBuilder();
        report.append("# 外部调用报告\n\n");

        for (String method : methods) {
            List<BoundaryEntity> externals = boundaryRepo.findByRepoIdAndFullMethod(repoId, method).stream()
                    .filter(b -> "HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType()) || "MQ".equals(b.getBoundaryType()))
                    .collect(Collectors.toList());
            if (!externals.isEmpty()) {
                report.append("## ").append(shortMethod(method)).append("\n");
                for (BoundaryEntity b : externals) {
                    String ctx = b.getContext() != null ? b.getContext() : "";
                    report.append("- [").append(b.getBoundaryType()).append("] ").append(ctx.split("\n")[0]).append("\n");
                    // URL
                    for (String line : ctx.split("\n")) {
                        if (line.contains("URL:")) report.append("  ").append(line.trim()).append("\n");
                    }
                }
                report.append("\n");
            }
        }
        return report.toString();
    }

    private void collectMethods(CallGraphEngine.CallTreeNodeDTO node, List<String> methods, Set<String> visited) {
        if (node == null || visited.contains(node.fullMethod())) return;
        visited.add(node.fullMethod());
        methods.add(node.fullMethod());
        if (node.children() != null) {
            for (var child : node.children()) collectMethods(child, methods, visited);
        }
    }

    private EndpointSearchResult toSearchResult(ApiEndpointEntity e) {
        // 从 chunks 表获取注释
        String comment = "";
        // 简化：用方法名生成描述
        String methodName = e.getFullMethod().contains(":") ? e.getFullMethod().substring(e.getFullMethod().lastIndexOf(':') + 1) : e.getFullMethod();
        int paren = methodName.indexOf('(');
        if (paren > 0) methodName = methodName.substring(0, paren);

        String shortClass = e.getClassName() != null && e.getClassName().contains(".")
                ? e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) : (e.getClassName() != null ? e.getClassName() : "");

        return new EndpointSearchResult(
                e.getId(), e.getEndpointType(), e.getHttpMethod(), e.getUrlPath(),
                e.getFullMethod(), shortClass, methodName, comment, 0
        );
    }

    private String shortMethod(String fullMethod) {
        String cls = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(0, fullMethod.lastIndexOf(':')) : fullMethod;
        String shortCls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
        String method = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(fullMethod.lastIndexOf(':') + 1) : "";
        int p = method.indexOf('(');
        if (p > 0) method = method.substring(0, p);
        return shortCls + "." + method;
    }

    // ========== DTOs ==========

    public static class EndpointSearchResult {
        public Long id;
        public String endpointType;
        public String httpMethod;
        public String urlPath;
        public String fullMethod;
        public String className;
        public String methodName;
        public String comment;
        public int score;

        public EndpointSearchResult(Long id, String endpointType, String httpMethod, String urlPath,
                                     String fullMethod, String className, String methodName, String comment, int score) {
            this.id = id; this.endpointType = endpointType; this.httpMethod = httpMethod; this.urlPath = urlPath;
            this.fullMethod = fullMethod; this.className = className; this.methodName = methodName;
            this.comment = comment; this.score = score;
        }
    }

    public record QAResponse(String answer, List<String> references, boolean cached) {}

    /** AI 分析出的意图结构 */
    public record IntentResult(String intentType, String intentLabel, String summary,
                                String entity, List<String> focusOn, List<String> keywords,
                                int confidence) {}

    /** 前端回传的用户已确认意图 */
    public record IntentConfirmation(String intentType, String intentLabel, String clarification) {}

    public record SmartQAResponse(String answer, List<String> references, boolean cached,
                                   List<String> keywords, List<MatchedEndpoint> matchedEndpoints,
                                   boolean needsConfirmation, IntentResult intentResult,
                                   boolean needsIntentConfirmation) {}
    public record MatchedEndpoint(String fullMethod, String className, String methodName,
                                   String endpointType, String httpMethod, String urlPath,
                                   String repoName, int score) {}
    private record CachedAnswer(String answer, List<String> references) {
        private static final long TTL_MS = 30 * 60 * 1000L;
        private final long createdAt = System.currentTimeMillis();
        boolean isExpired() { return System.currentTimeMillis() - createdAt > TTL_MS; }
    }

    private static class CachedSummary {
        final String text;
        final long createdAt = System.currentTimeMillis();
        CachedSummary(String text) { this.text = text; }
        boolean isExpired() { return System.currentTimeMillis() - createdAt > 30 * 60 * 1000L; }
    }
}
