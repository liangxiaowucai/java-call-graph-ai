package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.LogAnalyzer;
import com.adrninistrator.javacg2.platform.service.MockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CallChainCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(CallChainCodeGenerator.class);

    private final CallGraphEngine callGraphEngine;
    private final BoundaryRepo boundaryRepo;
    private final RepositoryRepo repositoryRepo;
    private final MockService mockService;

    // 精简时要过滤的样板代码模式
    private static final List<String> BOILERPLATE_PATTERNS = List.of(
            "log.info", "log.debug", "log.warn", "log.error", "log.trace",
            "logger.info", "logger.debug", "logger.warn", "logger.error",
            "LOG.info", "LOG.debug", "LOG.warn", "LOG.error",
            ".getId()", ".getName()", ".setId(", ".setName(",
            "System.out.print", "System.err.print",
            "@Override", "@Autowired", "@Resource", "@Inject",
            "@Slf4j", "@Service", "@Component", "@Repository", "@Controller",
            "@RequestMapping", "@GetMapping", "@PostMapping",
            "import ", "package "
    );

    // 精简时保留的关键词（包含这些的行一定保留）
    private static final List<String> KEEP_PATTERNS = List.of(
            "return ", "throw ", "if (", "if(", "else ", "for (", "for(",
            "while (", "while(", "switch ", "case ",
            "new ", ".save(", ".insert(", ".update(", ".delete(", ".select(",
            ".execute(", ".query(", ".call(", ".invoke(",
            ".send(", ".post(", ".get(", ".put(", ".exchange(",
            "try {", "catch (", "finally {"
    );

    public CallChainCodeGenerator(CallGraphEngine callGraphEngine, BoundaryRepo boundaryRepo,
                                   RepositoryRepo repositoryRepo, MockService mockService) {
        this.callGraphEngine = callGraphEngine;
        this.boundaryRepo = boundaryRepo;
        this.repositoryRepo = repositoryRepo;
        this.mockService = mockService;
    }

    /**
     * 生成调用链代码
     * @param logDiagnosisStatuses 日志诊断结果（可为 null，表示无日志信息）
     */
    public String generate(Long repoId, String entryMethod, Map<String, String> logDiagnosisStatuses) {
        var repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return "// 仓库不存在";

        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 15);
        if (tree.root() == null) return "// 调用树为空";

        // 收集所有节点
        List<CallGraphEngine.CallTreeNodeDTO> nodes = new ArrayList<>();
        collectNodes(tree.root(), nodes, new HashSet<>());

        boolean hasLogDiagnosis = logDiagnosisStatuses != null && !logDiagnosisStatuses.isEmpty();

        // 异常节点集合
        Set<String> errorMethods = new HashSet<>();
        if (hasLogDiagnosis) {
            logDiagnosisStatuses.forEach((method, status) -> {
                if ("ERROR".equals(status)) errorMethods.add(method);
            });
        }

        StringBuilder code = new StringBuilder();

        // 头部注释
        code.append("/**\n");
        code.append(" * 调用链代码 — ").append(extractShort(entryMethod)).append("\n");
        code.append(" * 节点: ").append(nodes.size()).append(" 个");
        if (hasLogDiagnosis) {
            code.append(" | 异常: ").append(errorMethods.size()).append(" 个");
        }
        code.append("\n");
        code.append(" * 模式: ").append(hasLogDiagnosis ? "日志诊断（异常节点完整保留）" : "精简（仅保留核心逻辑）").append("\n");
        code.append(" *\n");
        code.append(" * ✅ 正常节点 → 精简：方法签名 + 核心业务逻辑\n");
        code.append(" * ❌ 异常节点 → 完整保留源码，便于排查\n");
        code.append(" * ❓ 未识别节点 → 精简\n");
        code.append(" */\n\n");

        // Mock 常量
        Set<String> mockMethods = new HashSet<>();
        for (CallGraphEngine.CallTreeNodeDTO node : nodes) {
            List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, node.fullMethod());
            for (BoundaryEntity b : boundaries) {
                if (("HTTP".equals(b.getBoundaryType()) || "GRPC".equals(b.getBoundaryType()) || "MQ".equals(b.getBoundaryType()))
                        && b.getCalleeMethod() != null && mockMethods.add(b.getCalleeMethod())) {
                    MockService.MockConfig mock = mockService.getMock(repoId, b.getCalleeMethod());
                    String varName = toVarName(b.getCalleeMethod());
                    code.append("// [").append(b.getBoundaryType()).append("] ").append(extractShort(b.getCalleeMethod()));
                    if (b.getContext() != null) {
                        String firstLine = b.getContext().split("\n")[0];
                        if (firstLine.contains("URL:")) code.append(" | ").append(firstLine.substring(firstLine.indexOf("URL:")));
                    }
                    code.append("\n");
                    code.append("static final String ").append(varName).append("_RESP = ");
                    code.append("\"").append(escape(mock != null ? mock.mockResponse() : "{\"code\":200}")).append("\";\n\n");
                }
            }
        }

        // 逐节点输出
        for (int i = 0; i < nodes.size(); i++) {
            CallGraphEngine.CallTreeNodeDTO node = nodes.get(i);
            String status = hasLogDiagnosis
                    ? logDiagnosisStatuses.getOrDefault(node.fullMethod(), "UNKNOWN")
                    : "NORMAL";
            boolean isError = "ERROR".equals(status);
            boolean keepFull = isError; // 异常节点完整保留

            String statusIcon = isError ? "❌" : "UNKNOWN".equals(status) && hasLogDiagnosis ? "❓" : "✅";

            // 边界标签
            List<BoundaryEntity> boundaries = boundaryRepo.findByRepoIdAndFullMethod(repoId, node.fullMethod());
            String boundaryTags = boundaries.stream()
                    .map(b -> "[" + b.getBoundaryType() + "]")
                    .distinct()
                    .collect(Collectors.joining(" "));

            code.append("// ──── ").append(statusIcon).append(" 步骤 ").append(i + 1).append(": ")
                    .append(extractShort(node.fullMethod()));
            if (!boundaryTags.isEmpty()) code.append(" ").append(boundaryTags);
            code.append(" ────\n");

            if (isError) {
                String errorMsg = logDiagnosisStatuses.get(node.fullMethod() + ".error");
                if (errorMsg != null) {
                    code.append("// ⚠️ 异常: ").append(errorMsg).append("\n");
                }
            }

            // 读取源码
            String source = callGraphEngine.getMethodSource(repoId, node.fullMethod());
            if (source == null) {
                code.append("// (源码未找到，占位保留调用顺序)\n");
                code.append("// ").append(node.fullMethod()).append("\n\n");
                continue;
            }

            if (keepFull) {
                // 异常节点：完整保留
                code.append("// ↓↓↓ 完整源码（异常节点）↓↓↓\n");
                for (String line : source.split("\n")) {
                    code.append(line).append("\n");
                }
                code.append("// ↑↑↑ 完整源码结束 ↑↑↑\n\n");
            } else {
                // 伪代码风格精简
                code.append(generatePseudoCode(source, node.fullMethod(), boundaries));
                code.append("\n");
            }
        }

        return code.toString();
    }

    /** 无日志版本 */
    public String generate(Long repoId, String entryMethod) {
        return generate(repoId, entryMethod, null);
    }

    private boolean isBoilerplate(String line) {
        for (String pattern : BOILERPLATE_PATTERNS) {
            if (line.contains(pattern)) return true;
        }
        // 纯注释
        if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) return true;
        // 空的 try/catch 样板
        if (line.equals("try {") || line.equals("} catch (Exception e) {") || line.equals("} finally {")) return false; // 保留
        return false;
    }

    private boolean isKeyLogic(String line) {
        for (String pattern : KEEP_PATTERNS) {
            if (line.contains(pattern)) return true;
        }
        // 赋值语句
        if (line.contains(" = ") && !line.startsWith("//")) return true;
        return false;
    }

    /**
     * 生成伪代码风格的精简代码
     * 保留方法签名，核心逻辑用真实代码，样板代码用自然语言注释替代
     */
    private String generatePseudoCode(String source, String fullMethod, List<BoundaryEntity> boundaries) {
        String[] lines = source.split("\n");
        StringBuilder pseudo = new StringBuilder();

        // 提取边界类型集合
        Set<String> boundaryTypes = boundaries.stream()
                .map(BoundaryEntity::getBoundaryType).collect(Collectors.toSet());

        boolean foundSignature = false;
        boolean inTryCatch = false;
        int consecutiveSkipped = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 跳过 package/import/类声明/注解
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue;
            if (trimmed.startsWith("@") && !trimmed.startsWith("@Override")) continue;
            if (trimmed.startsWith("public class ") || trimmed.startsWith("private class ")) continue;

            // 方法签名 → 原样保留
            if (!foundSignature && trimmed.contains("(") && isMethodSignature(trimmed)) {
                pseudo.append(line).append("\n");
                foundSignature = true;
                consecutiveSkipped = 0;
                continue;
            }

            // 核心逻辑行 → 原样保留
            if (isKeyLogic(trimmed)) {
                if (consecutiveSkipped > 0) {
                    pseudo.append("    // ... ").append(consecutiveSkipped).append(" 行样板代码省略 ...\n");
                    consecutiveSkipped = 0;
                }
                pseudo.append(line).append("\n");
                continue;
            }

            // 变量赋值（含 new）→ 原样保留
            if (trimmed.contains(" = ") && !isBoilerplate(trimmed)) {
                if (consecutiveSkipped > 0) {
                    pseudo.append("    // ... ").append(consecutiveSkipped).append(" 行样板代码省略 ...\n");
                    consecutiveSkipped = 0;
                }
                pseudo.append(line).append("\n");
                continue;
            }

            // 闭合大括号 → 保留
            if (trimmed.equals("}") || trimmed.equals("};")) {
                if (consecutiveSkipped > 0) {
                    pseudo.append("    // ... ").append(consecutiveSkipped).append(" 行样板代码省略 ...\n");
                    consecutiveSkipped = 0;
                }
                pseudo.append(line).append("\n");
                continue;
            }

            // 其他行 → 计数跳过
            consecutiveSkipped++;
        }

        if (consecutiveSkipped > 0) {
            pseudo.append("    // ... ").append(consecutiveSkipped).append(" 行省略 ...\n");
        }

        // 如果什么都没生成，至少给个占位
        if (pseudo.length() == 0) {
            pseudo.append("// → ").append(extractShort(fullMethod)).append("(...)");
            if (!boundaryTypes.isEmpty()) {
                pseudo.append("  // 包含: ").append(String.join(", ", boundaryTypes));
            }
            pseudo.append("\n");
        }

        return pseudo.toString();
    }

    private boolean isMethodSignature(String line) {
        return (line.startsWith("public ") || line.startsWith("private ") || line.startsWith("protected ")
                || line.startsWith("static ") || line.startsWith("void ") || line.startsWith("String ")
                || line.startsWith("int ") || line.startsWith("long ") || line.startsWith("boolean ")
                || line.startsWith("List<") || line.startsWith("Map<") || line.startsWith("Optional<")
                || line.startsWith("ResponseEntity") || line.startsWith("Object "));
    }

    private void collectNodes(CallGraphEngine.CallTreeNodeDTO node, List<CallGraphEngine.CallTreeNodeDTO> result, Set<String> visited) {
        if (node == null || visited.contains(node.fullMethod())) return;
        visited.add(node.fullMethod());
        result.add(node);
        if (node.children() != null) {
            for (CallGraphEngine.CallTreeNodeDTO child : node.children()) {
                collectNodes(child, result, visited);
            }
        }
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) if (ch == c) count++;
        return count;
    }

    private String toVarName(String fullMethod) {
        String s = extractShort(fullMethod).replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private String extractShort(String fullMethod) {
        String className = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(0, fullMethod.lastIndexOf(':')) : fullMethod;
        String shortClass = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        String methodName = fullMethod.lastIndexOf(':') > 0 ? fullMethod.substring(fullMethod.lastIndexOf(':') + 1) : "";
        int paren = methodName.indexOf('(');
        if (paren > 0) methodName = methodName.substring(0, paren);
        return shortClass + "." + methodName;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
