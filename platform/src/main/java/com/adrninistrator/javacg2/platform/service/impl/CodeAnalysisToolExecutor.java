package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Claude function calling 工具执行器。
 * 每个工具对应一个方法，工具名称与 ToolDefinition.name() 对应。
 */
@Component
public class CodeAnalysisToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CodeAnalysisToolExecutor.class);
    private static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";

    private final CallGraphEngine callGraphEngine;
    private final BoundaryRepo boundaryRepo;

    public CodeAnalysisToolExecutor(CallGraphEngine callGraphEngine, BoundaryRepo boundaryRepo) {
        this.callGraphEngine = callGraphEngine;
        this.boundaryRepo = boundaryRepo;
    }

    /**
     * 执行工具调用，返回结果字符串给 Claude 的 tool_result。
     *
     * @param repoId   仓库 ID
     * @param toolName 工具名称
     * @param input    工具入参（Claude 提供的 JSON）
     * @return 工具执行结果，Claude 能理解的文本格式
     */
    public String execute(Long repoId, String toolName, JsonNode input) {
        try {
            return switch (toolName) {
                case "getMethodSource"  -> executeGetMethodSource(repoId, input);
                case "getCallees"       -> executeGetCallees(repoId, input);
                case "getCallers"       -> executeGetCallers(repoId, input);
                case "getBoundaries"    -> executeGetBoundaries(repoId, input);
                default -> "UNKNOWN_TOOL: " + toolName;
            };
        } catch (Exception e) {
            logger.warn("[ToolExecutor] 工具执行失败 tool={}: {}", toolName, e.getMessage());
            return "TOOL_ERROR: " + e.getMessage();
        }
    }

    // ── 工具实现 ─────────────────────────────────────────────────────────────

    private String executeGetMethodSource(Long repoId, JsonNode input) {
        String fullMethod = input.path("fullMethod").asText(null);
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        String source = callGraphEngine.getMethodSource(repoId, fullMethod);
        if (source == null) {
            return SOURCE_NOT_FOUND + ": " + fullMethod + "\n（可能是第三方库、框架生成代码或 JDK 方法，无法获取源码）";
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
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(extractShortRef(fullMethod)).append(" 调用的方法（").append(callees.size()).append(" 个）:\n");
        for (var callee : callees) {
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
                String ctx = b.getContext().split("\n")[0];
                sb.append(" ").append(ctx);
            }
            if (b.getCalleeMethod() != null) sb.append(" → ").append(b.getCalleeMethod());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private String extractShortRef(String fullMethod) {
        // com.example.Foo:bar(String) → Foo.bar
        int colonIdx = fullMethod.lastIndexOf(':');
        String cls = colonIdx > 0 ? fullMethod.substring(0, colonIdx) : fullMethod;
        String method = colonIdx > 0 ? fullMethod.substring(colonIdx + 1) : "";
        int paren = method.indexOf('(');
        if (paren > 0) method = method.substring(0, paren);
        int dotIdx = cls.lastIndexOf('.');
        String shortCls = dotIdx >= 0 ? cls.substring(dotIdx + 1) : cls;
        return shortCls + "." + method;
    }

    // ── 工具定义（供 QAEngineImpl 引用）────────────────────────────────────────

    public static List<ClaudeApiClient.ToolDefinition> buildToolDefinitions() {
        return List.of(
            new ClaudeApiClient.ToolDefinition(
                "getMethodSource",
                "获取指定方法的完整源码。当你需要深入了解某个方法的实现逻辑时调用。" +
                "如果方法是第三方库或 JDK 方法，可能返回 SOURCE_NOT_FOUND。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名，格式：类全限定名:方法名(参数类型列表)，例如：com.example.Foo:bar(java.lang.String)",
                    true))
            ),
            new ClaudeApiClient.ToolDefinition(
                "getCallees",
                "获取指定方法调用的所有子方法列表（直接调用，不递归展开）。" +
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
                "获取指定方法的边界点信息（DB 操作、HTTP 调用、MQ 消息、缓存等外部依赖）。" +
                "用于了解某个方法有哪些外部依赖，排查外部调用失败的问题。",
                List.of(new ClaudeApiClient.ToolParam(
                    "fullMethod", "string",
                    "方法的完整签名",
                    true))
            )
        );
    }
}
