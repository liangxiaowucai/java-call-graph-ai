package com.adrninistrator.javacg2.platform.service;

import java.util.List;

public interface CallGraphEngine {

    /** 获取入口点列表 */
    List<EntryPointDTO> getEntryPoints(Long repoId);

    /** 从入口点展开调用树 */
    CallTreeDTO expandCallTree(Long repoId, String entryMethod, int maxDepth);

    /** 获取方法的上游调用方 */
    List<CallerDTO> getCallers(Long repoId, String fullMethod, int depth);

    /** 获取方法的下游被调用方 */
    List<CallerDTO> getCallees(Long repoId, String fullMethod, int depth);

    /** 获取方法源码 */
    String getMethodSource(Long repoId, String fullMethod);

    /** 获取方法源码 + 枚举解析 + 入参信息（含调用链上下文） */
    MethodSourceDTO getMethodSourceDetail(Long repoId, String fullMethod, String entryMethod);

    // ── 跨库追踪 ──────────────────────────────────────────────────────────────

    /** 跨库影响分析：向上追踪所有仓库中调用该方法的链路，找出受影响的入口点 */
    CrossRepoImpactDTO getCrossRepoImpact(String fullMethod, int maxDepth);

    /** 跨库调用链：向下追踪该方法的被调用方，跨仓库续接展开 */
    CrossRepoTreeDTO getCrossRepoCallTree(String fullMethod, int maxDepth);

    /**
     * 影响分析 + 调用点源码：向上追踪所有调用方直到入口点，
     * 并为每个调用方附带「调用当前方法那一行附近的源码片段」及返回类型信息，
     * 便于一次性判断改动是否破坏了上层调用方的契约假设。
     */
    ImpactWithSourceDTO getImpactWithSource(String fullMethod, int maxDepth, int snippetRadius);

    record EntryPointDTO(Long id, String endpointType, String httpMethod, String urlPath,
                          String fullMethod, String className) {}

    record MethodSourceDTO(
        String sourceCode,
        String methodSignature,
        List<String> enumValues,
        List<String> chainContext,
        List<ParamClassInfo> paramClasses,  // 入参实体类字段信息
        int startLine
    ) {}

    record ParamClassInfo(
        String className,
        String shortName,
        List<String> fields   // "fieldName: fieldType // 注释"
    ) {}

    record CallTreeDTO(CallTreeNodeDTO root, int totalNodes, int maxDepth, boolean hasCycle, List<AmbiguityWarning> warnings) {}

    record CallTreeNodeDTO(String fullMethod, String className, String methodName,
                            String callType, Integer lineNumber,
                            List<BoundaryDTO> boundaries,
                            List<CallTreeNodeDTO> children,
                            boolean isRecursive, boolean isLazyLoad, boolean ambiguous) {}

    record BoundaryDTO(String boundaryType, Integer lineNumber, String context) {}

    record CallerDTO(String fullMethod, String className, String callType, Integer lineNumber, int depth) {}

    // ── 跨库追踪 DTO ──────────────────────────────────────────────────────────

    /** 跨库影响分析结果：目标方法 + 受影响的调用方（按仓库分组）+ 受影响的入口点 */
    record CrossRepoImpactDTO(
        String targetMethod,
        int totalCallers,
        int affectedRepoCount,
        int affectedEndpointCount,
        boolean truncated,
        List<ImpactRepoGroup> repoGroups,
        List<AmbiguityWarning> warnings
    ) {}

    /** 单个仓库内受影响的内容 */
    record ImpactRepoGroup(
        Long repoId,
        String repoName,
        List<ImpactCaller> callers,
        List<ImpactEndpoint> endpoints
    ) {}

    /** 跨库追踪到的一个调用方 */
    record ImpactCaller(
        String fullMethod,
        String shortRef,
        String callType,
        Integer lineNumber,
        int depth,
        boolean isEndpoint
    ) {}

    /** 跨库追踪到的一个受影响入口点（Controller/MQ/定时任务）*/
    record ImpactEndpoint(
        String endpointType,
        String httpMethod,
        String urlPath,
        String fullMethod,
        String shortRef
    ) {}

    /** 跨库调用树结果 */
    record CrossRepoTreeDTO(
        CrossRepoNodeDTO root,
        int totalNodes,
        int maxDepth,
        boolean hasCycle,
        boolean truncated,
        List<AmbiguityWarning> warnings
    ) {}

    /** 跨库调用树节点（含所属仓库信息）*/
    record CrossRepoNodeDTO(
        String fullMethod,
        String shortRef,
        Long repoId,
        String repoName,
        String callType,
        Integer lineNumber,
        boolean crossesRepo,
        boolean isRecursive,
        boolean isLazyLoad,
        boolean external,
        boolean ambiguous,
        List<CrossRepoNodeDTO> children
    ) {}

    /**
     * 同名签名歧义告警：一个方法签名（包名+类名+方法+入参完全相同）在多个仓库都有定义，
     * 静态分析无法确定调用真正指向哪个实现。
     */
    record AmbiguityWarning(
        String fullMethod,
        List<AmbiguityLocation> locations
    ) {}

    /** 歧义签名的一处定义位置 */
    record AmbiguityLocation(
        Long repoId,
        String repoName,
        String filePath
    ) {}

    // ── 影响分析 + 调用点源码 DTO ──────────────────────────────────────────────

    /** 影响分析（含调用点源码）结果 */
    record ImpactWithSourceDTO(
        String targetMethod,
        int totalCallers,
        int affectedRepoCount,
        int affectedEndpointCount,
        boolean truncated,
        List<ImpactSourceCaller> callers,
        List<AmbiguityWarning> warnings
    ) {}

    /** 一个调用方 + 它调用目标方法处的源码上下文 */
    record ImpactSourceCaller(
        String fullMethod,
        String shortRef,
        Long repoId,
        String repoName,
        String callType,
        Integer lineNumber,
        int depth,
        boolean isEndpoint,
        String endpointType,
        String httpMethod,
        String urlPath,
        String callerReturnType,      // 调用方法的返回类型
        String calleeActualReturnType, // 被调用方法（目标方法）的实际返回类型
        String callSiteSnippet         // 调用点附近源码片段（lineNumber ± radius）
    ) {}
}
