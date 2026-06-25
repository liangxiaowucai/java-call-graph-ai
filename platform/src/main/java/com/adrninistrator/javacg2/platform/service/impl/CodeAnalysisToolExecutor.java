package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Claude function calling 工具执行器。
 * 每个工具对应一个方法，工具名称与 ToolDefinition.name() 对应。
 */
@Component
public class CodeAnalysisToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CodeAnalysisToolExecutor.class);
    private static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 噪点方法前缀/关键词：getter/setter/toString/hashCode/equals 等样板代码
    private static final Set<String> BOILERPLATE_METHODS = Set.of(
            "toString", "hashCode", "equals", "compareTo", "clone",
            "writeObject", "readObject", "serialVersionUID"
    );
    // 噪点工具类包名前缀
    private static final List<String> NOISE_PACKAGE_PREFIXES = List.of(
            "org.slf4j.", "org.apache.logging.", "java.util.stream.",
            "java.util.Objects.", "org.springframework.util.",
            "com.google.common.", "org.apache.commons.",
            "lombok."
    );

    private final CallGraphEngine callGraphEngine;
    private final BoundaryRepo boundaryRepo;
    private final ChunkRepo chunkRepo;
    private final ChainOutlineService chainOutlineService;
    private final ExternalCallFormatter externalCallFormatter;

    public CodeAnalysisToolExecutor(CallGraphEngine callGraphEngine, BoundaryRepo boundaryRepo, ChunkRepo chunkRepo,
                                    ChainOutlineService chainOutlineService, ExternalCallFormatter externalCallFormatter) {
        this.callGraphEngine = callGraphEngine;
        this.boundaryRepo = boundaryRepo;
        this.chunkRepo = chunkRepo;
        this.chainOutlineService = chainOutlineService;
        this.externalCallFormatter = externalCallFormatter;
    }

    /**
     * 执行工具调用，返回结果字符串给 Claude 的 tool_result。
     */
    public String execute(Long repoId, String toolName, JsonNode input) {
        try {
            return switch (toolName) {
                case "getChainOutline"    -> executeGetChainOutline(repoId, input);
                case "getMethodSource"    -> executeGetMethodSource(repoId, input);
                case "getCallees"         -> executeGetCallees(repoId, input);
                case "getCallers"         -> executeGetCallers(repoId, input);
                case "getBoundaries"      -> executeGetBoundaries(repoId, input);
                case "getConstants"       -> executeGetConstants(repoId, input);
                case "getExceptions"      -> executeGetExceptions(repoId, input);
                case "getParamClassDef"   -> executeGetParamClassDef(repoId, input);
                case "getImplementations" -> executeGetImplementations(repoId, input);
                default -> "UNKNOWN_TOOL: " + toolName;
            };
        } catch (Exception e) {
            logger.warn("[ToolExecutor] 工具执行失败 tool={}: {}", toolName, e.getMessage());
            return "TOOL_ERROR: " + e.getMessage();
        }
    }

    // ── 工具实现 ─────────────────────────────────────────────────────────────

    private String executeGetChainOutline(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        int maxNodes = input.path("maxNodes").asInt(300);
        return chainOutlineService.buildChainOutline(repoId, fullMethod, maxNodes);
    }

    private String executeGetMethodSource(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        String source = callGraphEngine.getMethodSource(repoId, fullMethod);
        if (source == null) {
            return SOURCE_NOT_FOUND + ": " + fullMethod + "\n（可能是第三方库、框架生成代码或 JDK 方法，无法获取源码）";
        }
        // 降噪：过滤纯 getter/setter/toString 等样板方法源码
        String methodName = extractMethodName(fullMethod);
        if (isGetterOrSetter(methodName) && source.lines().count() <= 5) {
            return "BOILERPLATE_METHOD: " + fullMethod + " 是 getter/setter 样板方法，无业务逻辑，跳过。";
        }
        // 提取短类名和方法名，方便 Claude 引用
        String shortRef = extractShortRef(fullMethod);
        return "// === " + shortRef + " ===\n" + source;
    }

    private String executeGetCallees(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        List<CallGraphEngine.CallerDTO> callees = callGraphEngine.getCallees(repoId, fullMethod, 1);
        if (callees.isEmpty()) {
            return "NO_CALLEES: " + fullMethod + " 没有调用其他方法（或所有被调方法已被过滤）";
        }
        // 降噪：过滤工具类调用和 getter/setter，只保留业务方法
        List<CallGraphEngine.CallerDTO> businessCallees = callees.stream()
                .filter(c -> !isNoiseMethod(c.fullMethod()))
                .collect(Collectors.toList());
        int filteredCount = callees.size() - businessCallees.size();

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 调用的业务方法（")
          .append(businessCallees.size()).append(" 个");
        if (filteredCount > 0) sb.append("，已过滤 ").append(filteredCount).append(" 个工具/样板方法");
        sb.append("）:\n");
        for (var callee : businessCallees) {
            sb.append("- ").append(callee.fullMethod())
              .append(" [").append(callee.callType() != null ? callee.callType() : "CALL").append("]");
            if (callee.lineNumber() != null) sb.append(" 行:").append(callee.lineNumber());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String executeGetCallers(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        List<CallGraphEngine.CallerDTO> callers = callGraphEngine.getCallers(repoId, fullMethod, 1);
        if (callers.isEmpty()) {
            return "NO_CALLERS: " + fullMethod + " 没有被其他方法调用（可能是入口方法）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// 调用 ").append(extractShortRef(fullMethod)).append(" 的方法（").append(callers.size()).append(" 个）:\n");
        for (var caller : callers) {
            sb.append("- ").append(caller.fullMethod());
            if (caller.lineNumber() != null) sb.append(" 行:").append(caller.lineNumber());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String executeGetBoundaries(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, fullMethod);
        if (boundaries.isEmpty()) {
            return "NO_BOUNDARIES: " + fullMethod + " 没有检测到边界点（DB/HTTP/MQ）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 的边界点（").append(boundaries.size()).append(" 个）:\n");
        for (BoundaryEntity b : boundaries) {
            sb.append("- [").append(b.getBoundaryType()).append("]");
            if (b.getLineNumber() != null) sb.append(" 行:").append(b.getLineNumber());
            if (b.getContext() != null) {
                // 展示完整的上下文（含 URL、SQL 等关键信息）
                sb.append("\n  ").append(b.getContext().replace("\n", "\n  "));
            }
            if (b.getCalleeMethod() != null) sb.append("\n  → ").append(b.getCalleeMethod());
            sb.append("\n");
        }
        // 装配外部 HTTP 调用的完整信息（系统名 + 完整URL + 用途），保证 AI 能直接给出可读的外部调用清单
        boolean hasHttp = boundaries.stream().anyMatch(b -> "HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType()));
        if (hasHttp) {
            ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).orElse(null);
            String external = externalCallFormatter.render(chunk);
            if (!external.isBlank()) {
                sb.append("\n## 外部调用（已装配 系统名+完整URL+用途，请按此格式全部列出）:\n").append(external);
            }
        }
        return sb.toString();
    }

    /**
     * 新增工具：获取方法中使用的常量值、枚举解析、配置 key。
     * 让 Claude 知道代码中的具体值（状态码、配置名、字面量等）。
     */
    private String executeGetConstants(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).orElse(null);
        if (chunk == null) {
            return "NO_DATA: " + fullMethod + " 没有找到对应的代码块数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 的业务数据:\n");

        // 常量值（constants 为 JSON [{value, resolvedValue, line, code, file}]）。
        // 跳过接口 path 字面量（以 / 开头）：它们归外部调用区，由 ExternalCallFormatter 统一展示。
        String constants = chunk.getConstants();
        if (constants != null && !constants.isBlank()) {
            StringBuilder constLines = new StringBuilder();
            try {
                com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(constants);
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode c : arr) {
                        String value = c.path("value").asText("");
                        if (value.isEmpty() || value.charAt(0) == '/') continue;
                        constLines.append("  ").append(value);
                        String resolved = c.path("resolvedValue").asText(null);
                        if (resolved != null && !resolved.isBlank()) constLines.append(" = ").append(resolved);
                        String file = c.path("file").asText(null);
                        int line = c.path("line").asInt(0);
                        if (file != null && line > 0) constLines.append(" @ ").append(file).append(":").append(line);
                        constLines.append("\n");
                    }
                }
            } catch (Exception e) {
                // 解析失败回退到原始展示
                for (String line : constants.split("\n")) {
                    if (!line.isBlank()) constLines.append("  ").append(line.trim()).append("\n");
                }
            }
            if (constLines.length() > 0) {
                sb.append("\n## 字符串常量:\n").append(constLines);
            }
        }

        // 解析后的外部 URL：装配成「系统名 + 完整URL + 用途」，而非半截 base URL
        String resolvedUrls = chunk.getResolvedUrls();
        String external = externalCallFormatter.render(chunk);
        if (!external.isBlank()) {
            sb.append("\n## 外部调用（系统名 + 完整URL + 用途，请按此格式全部列出）:\n").append(external);
        } else if (resolvedUrls != null && !resolvedUrls.isBlank()) {
            sb.append("\n## 外部调用 URL（已解析配置值）:\n");
            sb.append("  ").append(resolvedUrls.replace("\n", "\n  ")).append("\n");
        }

        if (constants == null && resolvedUrls == null) {
            return "NO_CONSTANTS: " + fullMethod + " 未检测到业务常量或配置引用";
        }
        return sb.toString();
    }

    /**
     * 新增工具：获取方法的业务错误码和异常抛出信息。
     * 让 Claude 知道什么条件下会抛出什么异常、返回什么错误码。
     */
    private String executeGetExceptions(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).orElse(null);
        if (chunk == null) {
            return "NO_DATA: " + fullMethod + " 没有找到对应的代码块数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 的异常和错误码:\n");

        boolean hasData = false;

        // 业务错误码（code + msg，JSON 数组格式）
        String errorCodes = chunk.getErrorCodes();
        if (errorCodes != null && !errorCodes.isBlank()) {
            sb.append("\n## 业务错误码（throw/return 语句中的 code+msg）:\n");
            sb.append("  ").append(errorCodes.replace("\n", "\n  ")).append("\n");
            hasData = true;
        }

        // 异常类型
        String exceptions = chunk.getExceptions();
        if (exceptions != null && !exceptions.isBlank()) {
            sb.append("\n## 异常类型（throw/catch 的异常类）:\n");
            for (String line : exceptions.split("\n")) {
                if (!line.isBlank()) sb.append("  ").append(line.trim()).append("\n");
            }
            hasData = true;
        }

        if (!hasData) {
            return "NO_EXCEPTIONS: " + fullMethod + " 未检测到业务错误码或异常抛出";
        }
        return sb.toString();
    }

    /**
     * 新增工具：获取入参实体类的字段定义和校验注解。
     * 让 Claude 知道接口入参有哪些字段、哪些必填、什么校验规则。
     */
    private String executeGetParamClassDef(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        // 利用已有的 getMethodSourceDetail 获取入参实体类信息
        var detail = callGraphEngine.getMethodSourceDetail(repoId, fullMethod, null);
        if (detail == null) {
            return "NO_DATA: " + fullMethod + " 未找到方法详情";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 的入参信息:\n");

        // 方法签名
        if (detail.methodSignature() != null) {
            sb.append("\n## 方法签名:\n  ").append(detail.methodSignature()).append("\n");
        }

        // 入参实体类字段
        if (detail.paramClasses() != null && !detail.paramClasses().isEmpty()) {
            sb.append("\n## 入参实体类字段（* 表示必填）:\n");
            for (var pc : detail.paramClasses()) {
                sb.append("\n  ").append(pc.shortName()).append(":\n");
                for (String field : pc.fields()) {
                    sb.append("    ").append(field).append("\n");
                }
            }
        } else {
            sb.append("\n（入参为基础类型或无实体类定义）\n");
        }

        // 枚举值解析
        if (detail.enumValues() != null && !detail.enumValues().isEmpty()) {
            sb.append("\n## 枚举/常量值解析:\n");
            for (String ev : detail.enumValues()) {
                sb.append("  ").append(ev).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 新增工具：获取接口方法的所有实现类。
     * 解决多态分派问题，让 Claude 知道调用接口方法时实际走哪个实现。
     */
    private String executeGetImplementations(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        // 使用 getCallees 中深度 1 + 类型 IMPL/_ITF 来找实现
        List<CallGraphEngine.CallerDTO> callees = callGraphEngine.getCallees(repoId, fullMethod, 1);
        List<CallGraphEngine.CallerDTO> impls = callees.stream()
                .filter(c -> "IMPL".equals(c.callType()) || "_ITF".equals(c.callType()) || "INT".equals(c.callType()))
                .collect(Collectors.toList());

        // 也检查 chunk 中同签名但不同类的实现
        String methodName = fullMethod.contains(":") ? fullMethod.substring(fullMethod.indexOf(':') + 1) : fullMethod;
        List<ChunkEntity> allImpls = chunkRepo.findByRepoId(repoId).stream()
                .filter(c -> c.getFullMethod() != null && c.getFullMethod().endsWith(":" + methodName))
                .filter(c -> !c.getFullMethod().equals(fullMethod))
                .collect(Collectors.toList());

        if (impls.isEmpty() && allImpls.isEmpty()) {
            return "NO_IMPLEMENTATIONS: " + fullMethod + " 没有找到实现类（可能本身就是具体实现而非接口/抽象方法）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 的实现类:\n");

        Set<String> seen = new HashSet<>();
        if (!impls.isEmpty()) {
            sb.append("\n## 调用图中的实现分派:\n");
            for (var impl : impls) {
                sb.append("  → ").append(impl.fullMethod()).append(" [").append(impl.callType()).append("]\n");
                seen.add(impl.fullMethod());
            }
        }
        if (!allImpls.isEmpty()) {
            List<ChunkEntity> extras = allImpls.stream()
                    .filter(c -> !seen.contains(c.getFullMethod()))
                    .collect(Collectors.toList());
            if (!extras.isEmpty()) {
                sb.append("\n## 同签名的其他实现（可能的多态分派目标）:\n");
                for (ChunkEntity c : extras) {
                    sb.append("  → ").append(c.getFullMethod());
                    if (c.getClassName() != null) sb.append(" (").append(c.getClassName()).append(")");
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

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

    private String extractMethodName(String fullMethod) {
        int colonIdx = fullMethod.lastIndexOf(':');
        String method = colonIdx > 0 ? fullMethod.substring(colonIdx + 1) : fullMethod;
        int paren = method.indexOf('(');
        return paren > 0 ? method.substring(0, paren) : method;
    }

    private boolean isGetterOrSetter(String methodName) {
        if (methodName == null) return false;
        return (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)))
            || (methodName.startsWith("set") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)))
            || (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2)))
            || BOILERPLATE_METHODS.contains(methodName);
    }

    private boolean isNoiseMethod(String fullMethod) {
        if (fullMethod == null) return false;
        // 工具类包名过滤
        for (String prefix : NOISE_PACKAGE_PREFIXES) {
            if (fullMethod.startsWith(prefix)) return true;
        }
        // getter/setter 过滤
        String methodName = extractMethodName(fullMethod);
        if (isGetterOrSetter(methodName)) return true;
        // log 方法过滤
        if (fullMethod.contains("Logger:") || fullMethod.contains("LogFactory:")) return true;
        return false;
    }

    // ── 工具定义（供 QAEngineImpl 引用）────────────────────────────────────────

    public static List<ClaudeApiClient.ToolDefinition> buildToolDefinitions() {
        return List.of(
            new ClaudeApiClient.ToolDefinition(
                "getChainOutline",
                "【优先调用】获取入口方法的调用链『地图』：一份轻量的缩进大纲，列出整条链上的业务方法，" +
                "并对每个方法标注外部边界/数据 flag（HTTP/DB/CACHE/MQ/常量/异常）。不含源码、体量小。" +
                "用法：先用它定位哪些节点有外部调用/关键数据，再用 getBoundaries/getConstants/getMethodSource 对这些节点读取明细，" +
                "避免遗漏下游 HTTP/Redis/MQ 细节。truncated=true 表示节点过多被截断。",
                List.of(
                    new ClaudeApiClient.ToolParam(
                        "fullMethod", "string",
                        "入口方法的完整签名，格式：类全限定名:方法名(参数类型列表)",
                        true),
                    new ClaudeApiClient.ToolParam(
                        "maxNodes", "integer",
                        "地图节点上限，默认 300，一般无需指定",
                        false))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getMethodSource",
                "获取指定方法的完整源码。当你需要深入了解某个方法的实现逻辑时调用。" +
                "如果方法是 getter/setter 样板方法会提示跳过。" +
                "如果方法是第三方库或 JDK 方法，返回 SOURCE_NOT_FOUND。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名，格式：类全限定名:方法名(参数类型列表)，例如：com.example.Foo:bar(java.lang.String)",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getCallees",
                "获取指定方法调用的所有业务子方法列表（已过滤 getter/setter/日志/工具类等噪点）。" +
                "用于了解某个方法的下一层调用，确定下一步需要深入分析哪些子方法。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getCallers",
                "获取调用指定方法的所有上游方法列表。" +
                "用于排查某个方法被谁调用、追踪问题的调用来源。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getBoundaries",
                "获取指定方法的外部依赖边界点详情（DB/SQL操作、HTTP外部调用URL、MQ消息、缓存等）。" +
                "返回完整的上下文信息，包括 SQL 语句、调用 URL、配置 key 等。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getConstants",
                "获取指定方法中使用的业务常量、字符串字面量、配置 key 引用和已解析的外部 URL。" +
                "用于了解代码中的具体值：状态码、错误信息模板、配置项名称、外部服务地址等。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getExceptions",
                "获取指定方法的业务错误码（code+msg）和异常抛出/捕获信息。" +
                "用于排错场景：了解方法可能返回哪些错误码、什么条件下抛出异常。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getParamClassDef",
                "获取指定方法的入参实体类字段定义、校验注解（@NotNull/@Size等）和枚举值解析。" +
                "用于对接指南场景：了解接口需要传哪些字段、每个字段的类型和约束。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getImplementations",
                "获取接口/抽象方法的所有实现类列表（解决多态分派问题）。" +
                "当发现调用的是接口方法时，用此工具找到实际运行的实现类，再用 getMethodSource 读取实现源码。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "接口或抽象方法的完整签名",
                    true))
            )
        );
    }
}
