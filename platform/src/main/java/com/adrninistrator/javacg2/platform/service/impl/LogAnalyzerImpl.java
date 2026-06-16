package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import com.adrninistrator.javacg2.platform.repository.CallGraphRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.LogAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogAnalyzerImpl implements LogAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(LogAnalyzerImpl.class);

    private final CallGraphEngine callGraphEngine;
    private final CallGraphRepo callGraphRepo;

    // 匹配堆栈帧: at com.example.Foo.bar(Foo.java:123)
    private static final Pattern STACK_FRAME = Pattern.compile(
            "at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([\\w.]+):(\\d+)\\)");

    // 匹配异常类型: java.lang.NullPointerException: message
    private static final Pattern EXCEPTION_LINE = Pattern.compile(
            "^(\\S*(?:Exception|Error|Throwable))(?::\\s*(.*))?$", Pattern.MULTILINE);

    // 匹配 URL: GET /api/xxx 或 POST /api/xxx 或 url=/api/xxx
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:GET|POST|PUT|DELETE|PATCH)\\s+(/[\\w/.-]+)|url=(/[\\w/.-]+)|\"(/[\\w/.-]+)\"");

    // 匹配 ERROR 级别日志行
    private static final Pattern ERROR_LOG = Pattern.compile(
            "^.*\\b(?:ERROR|FATAL|SEVERE)\\b.*$", Pattern.MULTILINE);

    public LogAnalyzerImpl(CallGraphEngine callGraphEngine, CallGraphRepo callGraphRepo) {
        this.callGraphEngine = callGraphEngine;
        this.callGraphRepo = callGraphRepo;
    }

    @Override
    public LogAnalysisResult analyze(Long repoId, String entryMethod, String logText) {
        // 判断用户粘贴的内容类型
        ContentType contentType = detectContentType(logText);
        logger.info("日志分析: 内容类型={}, 长度={}", contentType, logText.length());

        switch (contentType) {
            case STACK_TRACE:
                return analyzeStackTrace(repoId, entryMethod, logText);
            case JSON_REQUEST:
                return analyzeJsonInput(repoId, entryMethod, logText, "请求参数");
            case JSON_RESPONSE:
                return analyzeJsonInput(repoId, entryMethod, logText, "返回结果");
            case MIXED_LOG:
                return analyzeMixedLog(repoId, entryMethod, logText);
            default:
                return analyzeStackTrace(repoId, entryMethod, logText);
        }
    }

    private enum ContentType { STACK_TRACE, JSON_REQUEST, JSON_RESPONSE, MIXED_LOG }

    /** 判断内容类型 */
    private ContentType detectContentType(String text) {
        String trimmed = text.trim();
        // JSON 对象或数组
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            // 看里面有没有典型的响应字段
            if (trimmed.contains("\"code\"") || trimmed.contains("\"status\"") || trimmed.contains("\"message\"") || trimmed.contains("\"error\"")) {
                return ContentType.JSON_RESPONSE;
            }
            return ContentType.JSON_REQUEST;
        }
        // 有堆栈帧
        if (STACK_FRAME.matcher(text).find()) {
            // 检查是否混合了 JSON
            if (text.contains("{") && text.contains("}")) return ContentType.MIXED_LOG;
            return ContentType.STACK_TRACE;
        }
        // 有 ERROR 日志行
        if (ERROR_LOG.matcher(text).find()) {
            return ContentType.MIXED_LOG;
        }
        // 默认当混合日志
        return ContentType.MIXED_LOG;
    }

    /** 分析堆栈日志（原有逻辑） */
    private LogAnalysisResult analyzeStackTrace(Long repoId, String entryMethod, String logText) {
        String[] lines = logText.split("\n");

        // 1. 提取 URL
        String extractedUrl = extractUrl(logText);

        // 2. 提取异常信息
        String extractedException = extractException(logText);

        // 3. 提取堆栈帧中的类名.方法名
        Map<String, StackFrameInfo> stackFrames = extractStackFrames(lines);
        logger.info("从日志中提取到 {} 个堆栈帧", stackFrames.size());

        // 4. 提取 ERROR 日志行
        List<String> errorLines = extractErrorLines(logText);

        // 5. 展开调用树，获取所有节点
        CallGraphEngine.CallTreeDTO tree = callGraphEngine.expandCallTree(repoId, entryMethod, 20);
        List<String> allMethods = new ArrayList<>();
        collectMethods(tree.root(), allMethods);

        // 6. 为每个节点标记状态
        List<NodeStatus> statuses = new ArrayList<>();
        for (String method : allMethods) {
            String className = extractClassName(method);
            String methodName = extractMethodName(method);

            // 尝试多种 key 格式匹配（javacg2 用 : 分隔，堆栈用 . 分隔）
            StackFrameInfo frame = stackFrames.get(className + ":" + methodName);
            if (frame == null) frame = stackFrames.get(className + "." + methodName);
            if (frame == null) {
                String shortClassName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
                frame = stackFrames.get(shortClassName + ":" + methodName);
                if (frame == null) frame = stackFrames.get(shortClassName + "." + methodName);
            }

            if (frame != null) {
                // 在堆栈中找到了这个方法
                // 检查是否在异常堆栈的顶部附近（前3帧通常是出错点）
                if (frame.isNearTop) {
                    // 找到对应的 ERROR 日志
                    String errorMsg = findRelatedError(errorLines, className, methodName);
                    statuses.add(new NodeStatus(method, "ERROR",
                            errorMsg != null ? errorMsg : extractedException,
                            frame.lineInLog, frame.logSnippet));
                } else {
                    statuses.add(new NodeStatus(method, "OK", null, frame.lineInLog, frame.logSnippet));
                }
            } else {
                // 堆栈中没有这个方法 → 未识别
                statuses.add(new NodeStatus(method, "UNKNOWN", null, -1, null));
            }
        }

        // 7. 生成摘要
        long errorCount = statuses.stream().filter(s -> "ERROR".equals(s.status())).count();
        long okCount = statuses.stream().filter(s -> "OK".equals(s.status())).count();
        long unknownCount = statuses.stream().filter(s -> "UNKNOWN".equals(s.status())).count();
        String summary = String.format("分析完成: %d 个节点异常, %d 个正常, %d 个未识别", errorCount, okCount, unknownCount);

        return new LogAnalysisResult(statuses, summary, extractedUrl, extractedException);
    }

    /**
     * 分析 JSON 输入（请求参数或返回结果）
     * 将 JSON 字段与接口的入参/出参实体类对比，检查字段是否匹配、必填字段是否缺失
     */
    private LogAnalysisResult analyzeJsonInput(Long repoId, String entryMethod, String jsonText, String inputType) {
        // 获取接口的入参信息
        var sourceDetail = callGraphEngine.getMethodSourceDetail(repoId, entryMethod, entryMethod);

        List<NodeStatus> statuses = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        summary.append("检测到").append(inputType).append(" JSON\n\n");

        // 提取 JSON 中的所有 key
        Set<String> jsonKeys = extractJsonKeys(jsonText);
        summary.append("ℹ️ JSON 字段: ").append(String.join(", ", jsonKeys)).append("\n\n");

        if (sourceDetail != null && sourceDetail.paramClasses() != null && !sourceDetail.paramClasses().isEmpty()) {
            for (var pc : sourceDetail.paramClasses()) {
                summary.append("📦 实体类: ").append(pc.shortName()).append("\n");
                for (String field : pc.fields()) {
                    boolean required = field.startsWith("* ");
                    String fieldText = required ? field.substring(2) : field;
                    String fieldName = fieldText.contains(":") ? fieldText.substring(0, fieldText.indexOf(':')).trim() : fieldText.trim();

                    if (jsonKeys.contains(fieldName)) {
                        summary.append("  ✅ ").append(fieldText).append("\n");
                    } else if (required) {
                        summary.append("  ❌ ").append(fieldText).append(" ← 必填字段缺失\n");
                    } else {
                        summary.append("  ⚠️ ").append(fieldText).append(" ← 未提供\n");
                    }
                }
                summary.append("\n");
            }

            // 检查 JSON 中有但实体类中没有的字段
            Set<String> knownFields = new HashSet<>();
            for (var pc : sourceDetail.paramClasses()) {
                for (String field : pc.fields()) {
                    String f = field.startsWith("* ") ? field.substring(2) : field;
                    String name = f.contains(":") ? f.substring(0, f.indexOf(':')).trim() : f.trim();
                    knownFields.add(name);
                }
            }
            Set<String> unknownKeys = new LinkedHashSet<>(jsonKeys);
            unknownKeys.removeAll(knownFields);
            if (!unknownKeys.isEmpty()) {
                summary.append("❓ JSON 中未识别的字段: ").append(String.join(", ", unknownKeys)).append("\n");
            }
        } else {
            summary.append("⚠️ 未找到该接口的入参实体类信息\n");
        }

        // 给入口方法标记状态
        statuses.add(new NodeStatus(entryMethod, "OK", null, -1, inputType + " JSON 分析"));

        return new LogAnalysisResult(statuses, summary.toString(), null, null);
    }

    /** 分析混合日志（既有堆栈又有 JSON 或其他内容） */
    private LogAnalysisResult analyzeMixedLog(Long repoId, String entryMethod, String logText) {
        // 先跑堆栈分析
        LogAnalysisResult stackResult = analyzeStackTrace(repoId, entryMethod, logText);

        // 尝试提取嵌入的 JSON
        String embeddedJson = extractEmbeddedJson(logText);
        if (embeddedJson != null) {
            LogAnalysisResult jsonResult = analyzeJsonInput(repoId, entryMethod, embeddedJson, "日志中的JSON");
            // 合并摘要
            String mergedSummary = stackResult.summary() + "\n---\n" + jsonResult.summary();
            return new LogAnalysisResult(stackResult.nodeStatuses(), mergedSummary,
                    stackResult.extractedUrl(), stackResult.extractedException());
        }

        return stackResult;
    }

    /** 从 JSON 字符串中提取所有第一层 key */
    private Set<String> extractJsonKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        java.util.regex.Matcher m = Pattern.compile("\"(\\w+)\"\\s*:").matcher(json);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** 从混合日志中提取嵌入的 JSON 块 */
    private String extractEmbeddedJson(String logText) {
        int start = logText.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < logText.length(); i++) {
            char c = logText.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0) {
                String candidate = logText.substring(start, i + 1);
                if (candidate.length() > 10) return candidate;
            }
        }
        return null;
    }

    private void collectMethods(CallGraphEngine.CallTreeNodeDTO node, List<String> methods) {
        if (node == null) return;
        methods.add(node.fullMethod());
        if (node.children() != null) {
            for (CallGraphEngine.CallTreeNodeDTO child : node.children()) {
                collectMethods(child, methods);
            }
        }
    }

    private String extractUrl(String logText) {
        Matcher m = URL_PATTERN.matcher(logText);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return m.group(i);
            }
        }
        return null;
    }

    private String extractException(String logText) {
        Matcher m = EXCEPTION_LINE.matcher(logText);
        if (m.find()) {
            String type = m.group(1);
            String msg = m.group(2);
            return msg != null ? type + ": " + msg : type;
        }
        return null;
    }

    private Map<String, StackFrameInfo> extractStackFrames(String[] lines) {
        Map<String, StackFrameInfo> frames = new LinkedHashMap<>();
        int frameIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = STACK_FRAME.matcher(lines[i]);
            if (m.find()) {
                String fullClass = m.group(1);
                String method = m.group(2);

                StackFrameInfo info = new StackFrameInfo();
                info.lineInLog = i + 1;
                info.logSnippet = lines[i].trim();
                info.isNearTop = frameIndex < 3;
                info.sourceFile = m.group(3);
                info.sourceLine = Integer.parseInt(m.group(4));

                // 用完整类名.方法名做精确 key
                String exactKey = fullClass + ":" + method;
                frames.putIfAbsent(exactKey, info);

                // 也用短类名做 key（兼容 javacg2 格式）
                String shortClass = fullClass.contains(".") ? fullClass.substring(fullClass.lastIndexOf('.') + 1) : fullClass;
                frames.putIfAbsent(shortClass + ":" + method, info);

                // 兼容 . 分隔格式
                frames.putIfAbsent(fullClass + "." + method, info);
                frames.putIfAbsent(shortClass + "." + method, info);

                frameIndex++;
            } else {
                if (frameIndex > 0) frameIndex = 0;
            }
        }
        return frames;
    }

    private List<String> extractErrorLines(String logText) {
        List<String> errors = new ArrayList<>();
        Matcher m = ERROR_LOG.matcher(logText);
        while (m.find()) {
            errors.add(m.group().trim());
        }
        return errors;
    }

    private String findRelatedError(List<String> errorLines, String className, String methodName) {
        String shortClass = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        for (String line : errorLines) {
            if (line.contains(shortClass) || line.contains(methodName)) {
                return line;
            }
        }
        return errorLines.isEmpty() ? null : errorLines.get(0);
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

    private static class StackFrameInfo {
        int lineInLog;
        String logSnippet;
        boolean isNearTop;
        String sourceFile;
        int sourceLine;
    }
}
