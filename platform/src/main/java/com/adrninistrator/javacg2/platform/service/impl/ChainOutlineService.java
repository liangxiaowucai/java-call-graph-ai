package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用链「地图」服务：把整条调用链压成一份轻量的缩进大纲（方法名 + 外部边界/数据 flag），
 * 不含源码，体量小。供 AI 问答工具循环、MCP Server、调用链追踪等多处复用：
 * 先看地图定位 HTTP/DB/缓存/MQ 等关键节点，再按需读取明细，避免一次性把全链源码塞进上下文。
 */
@Service
public class ChainOutlineService {

    private static final Logger logger = LoggerFactory.getLogger(ChainOutlineService.class);

    /** 噪点包前缀：日志/工具类/JDK，不纳入地图 */
    private static final List<String> NOISE_PACKAGE_PREFIXES = List.of(
            "org.slf4j.", "org.apache.logging.", "java.", "javax.",
            "org.springframework.util.", "com.google.common.",
            "org.apache.commons.", "lombok.");

    /** 地图结果缓存 TTL（毫秒），同一 repo+method 短时间内复用，避免重复全展开 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final CallGraphEngine callGraphEngine;
    private final ChunkRepo chunkRepo;
    private final ExternalCallFormatter externalCallFormatter;

    private record CachedOutline(String text, long ts) {}
    private final Map<String, CachedOutline> outlineCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedOutline> eldest) {
                    return size() > 50;
                }
            });

    public ChainOutlineService(CallGraphEngine callGraphEngine, ChunkRepo chunkRepo,
                               ExternalCallFormatter externalCallFormatter) {
        this.callGraphEngine = callGraphEngine;
        this.chunkRepo = chunkRepo;
        this.externalCallFormatter = externalCallFormatter;
    }

    /**
     * 构建调用链地图。
     *
     * @param repoId     仓库 ID
     * @param fullMethod 入口方法完整签名
     * @param maxNodes   地图节点上限（防超大骨架），建议 300
     * @return 缩进大纲文本；未找到返回 NOT_FOUND，异常返回 OUTLINE_ERROR
     */
    public String buildChainOutline(Long repoId, String fullMethod, int maxNodes) {
        if (fullMethod == null || fullMethod.isBlank()) {
            return "INVALID_INPUT: fullMethod is required";
        }
        int limit = maxNodes > 0 ? maxNodes : 300;

        // 缓存命中（同 repo+method+limit，TTL 内）
        String cacheKey = repoId + "|" + fullMethod + "|" + limit;
        CachedOutline cached = outlineCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.ts() < CACHE_TTL_MS) {
            return cached.text();
        }

        CallGraphEngine.CallTreeDTO tree;
        try {
            // 全展开（与「调用链追踪」一致），保证下游所有外部调用节点及边界完整，不漏 HTTP/DB/缓存
            tree = callGraphEngine.expandCallTree(repoId, fullMethod, Integer.MAX_VALUE, true);
        } catch (Exception e) {
            logger.warn("[ChainOutline] 展开调用树失败 method={}: {}", fullMethod, e.getMessage());
            return "OUTLINE_ERROR: " + e.getMessage();
        }
        if (tree == null || tree.root() == null) {
            return "NOT_FOUND: " + fullMethod + "（未找到方法或无调用链数据）";
        }

        StringBuilder body = new StringBuilder();
        int[] count = {0};
        boolean[] truncated = {false};
        Set<String> expanded = new HashSet<>();
        // 外部依赖确定性汇总：type -> 去重的「方法 — 明细」条目（不依赖模型从树里逐个挑，保证全部列出）
        // 注意：汇总不从会折叠的调用树收集（菱形/重复子树折叠会漏边界），而是用 collectExternalCalls
        // 做一次按方法去重的完整遍历，保证链上所有 HTTP/DB/CACHE/MQ 都被列出。
        Map<String, LinkedHashSet<String>> depAgg = buildDepAgg(repoId, fullMethod);
        traverse(tree.root(), repoId, 0, body, count, truncated, expanded, limit, depAgg);

        StringBuilder out = new StringBuilder();
        out.append("// 调用链地图：缩进=层级；[标记]=外部边界/数据（HTTP/DB/CACHE/MQ/常量/异常）。\n");
        out.append("// 用法：对带标记的节点用 getBoundaries / getConstants / getMethodSource 读取明细后再下结论。\n");
        out.append(renderDepSummary(depAgg));
        out.append("节点数=").append(count[0]);
        if (truncated[0]) {
            out.append("，truncated=true（已达上限 ").append(limit)
               .append("，仍有未展开节点；回答涉及未展开部分时请显式说明“未覆盖”，不要臆测）");
        }
        out.append("\n").append(body);

        String result = out.toString();
        outlineCache.put(cacheKey, new CachedOutline(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * 构建确定性的外部依赖汇总：用 collectExternalCalls 做一次按方法去重的完整遍历，
     * 保证链上所有 HTTP/DB/CACHE/MQ 都被收集（不受调用树显示折叠影响）。
     *
     * <p>每个有边界的方法：
     * <ul>
     *   <li>HTTP：优先用 ExternalCallFormatter 装配「【系统名】HTTP调用 `完整URL` — 用途」；
     *       装配不出时回退到 boundary 的 context（含 📌 URL）。</li>
     *   <li>其它类型（DB/CACHE/MQ/RPC）：用 boundary 的 context。</li>
     * </ul>
     *
     * @return type -> 去重的「方法 — 明细」条目
     */
    private Map<String, LinkedHashSet<String>> buildDepAgg(Long repoId, String entryMethod) {
        Map<String, LinkedHashSet<String>> depAgg = new LinkedHashMap<>();
        Map<String, List<CallGraphEngine.BoundaryDTO>> external;
        try {
            external = callGraphEngine.collectExternalCalls(repoId, entryMethod);
        } catch (Exception e) {
            logger.warn("[ChainOutline] collectExternalCalls 失败 method={}: {}", entryMethod, e.getMessage());
            return depAgg;
        }

        for (var entry : external.entrySet()) {
            String fullMethod = entry.getKey();
            String ref = shortRef(fullMethod);
            List<CallGraphEngine.BoundaryDTO> boundaries = entry.getValue();

            // HTTP：优先用 formatter 装配完整 URL；该方法有 HTTP 边界但 formatter 装配不出时回退 context
            boolean hasHttp = boundaries.stream()
                    .anyMatch(b -> "HTTP".equals(b.boundaryType()) || "GRPC".equals(b.boundaryType()) || "RPC".equals(b.boundaryType()));
            Set<String> httpRendered = new LinkedHashSet<>();
            if (hasHttp) {
                ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, fullMethod).orElse(null);
                if (chunk != null) {
                    for (var call : externalCallFormatter.extract(chunk)) {
                        httpRendered.add(call.render());
                    }
                }
            }

            for (var b : boundaries) {
                String type = b.boundaryType();
                if (type == null || type.isBlank()) continue;
                if (("HTTP".equals(type) || "GRPC".equals(type) || "RPC".equals(type)) && !httpRendered.isEmpty()) {
                    continue; // HTTP 统一在下方用 formatter 渲染，避免与 context 重复
                }
                String ctx = oneLine(b.context(), 240);
                depAgg.computeIfAbsent(type, k -> new LinkedHashSet<>())
                      .add(ref + (ctx.isEmpty() ? "" : " — " + ctx));
            }
            for (String rendered : httpRendered) {
                depAgg.computeIfAbsent("HTTP", k -> new LinkedHashSet<>()).add(ref + " — " + rendered);
            }
        }
        return depAgg;
    }

    /** 渲染确定性的外部依赖汇总，保证链上所有 HTTP/DB/CACHE/MQ 都被列出 */
    private String renderDepSummary(Map<String, LinkedHashSet<String>> depAgg) {
        int total = depAgg.values().stream().mapToInt(Set::size).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("## 外部依赖汇总（共 ").append(total).append(" 个外部调用）\n");
        if (total == 0) {
            sb.append("（未检测到外部依赖边界）\n\n");
            return sb.toString();
        }
        for (var entry : depAgg.entrySet()) {
            sb.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
            for (String line : entry.getValue()) {
                sb.append("- ").append(line).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private void traverse(CallGraphEngine.CallTreeNodeDTO node, Long repoId, int depth, StringBuilder sb,
                          int[] count, boolean[] truncated, Set<String> expanded, int limit,
                          Map<String, LinkedHashSet<String>> depAgg) {
        if (node == null) return;
        if (isNoise(node.fullMethod())) return;
        if (count[0] >= limit) { truncated[0] = true; return; }
        count[0]++;

        String indent = "  ".repeat(Math.min(depth, 20));
        sb.append(indent).append("- ").append(shortRef(node.fullMethod()));

        List<String> flags = new ArrayList<>();
        if (node.boundaries() != null && !node.boundaries().isEmpty()) {
            LinkedHashSet<String> types = new LinkedHashSet<>();
            for (var b : node.boundaries()) {
                if (b.boundaryType() != null && !b.boundaryType().isBlank()) types.add(b.boundaryType());
            }
            flags.addAll(types);
        }
        if (node.constants() != null && !node.constants().isBlank()) flags.add("常量");
        if (node.exceptions() != null && !node.exceptions().isBlank()) flags.add("异常");
        if (!flags.isEmpty()) sb.append(" [").append(String.join(",", flags)).append("]");
        if (node.isRecursive()) sb.append(" (递归)");
        sb.append("\n");

        // 边界节点：内联关键明细（URL/SQL/缓存 key/错误码），让回答可直接给出具体值，不依赖再下钻。
        // 注意：外部依赖「汇总」由 buildDepAgg 统一负责（完整去重），这里只负责树内联展示，不再写 depAgg，
        // 避免格式不一造成同一调用重复计数。
        if (node.boundaries() != null && !node.boundaries().isEmpty()) {
            String detailIndent = indent + "    · ";
            for (var b : node.boundaries()) {
                String ctx = oneLine(b.context(), 240);
                if (!ctx.isEmpty()) {
                    sb.append(detailIndent).append("[").append(b.boundaryType()).append("] ").append(ctx).append("\n");
                }
            }
            // 从 chunk 装配完整外部调用（系统名 + 完整URL + 用途）内联展示
            ChunkEntity chunk = chunkRepo.findByRepoIdAndFullMethod(repoId, node.fullMethod()).orElse(null);
            if (chunk != null) {
                for (var call : externalCallFormatter.extract(chunk)) {
                    sb.append(detailIndent).append(call.render()).append("\n");
                }
                String errs = oneLine(chunk.getErrorCodes(), 240);
                if (!errs.isEmpty()) sb.append(detailIndent).append("错误码: ").append(errs).append("\n");
            }
        }

        // 递归节点或已展开过的同名方法：不再向下展开（避免重复与环）
        if (node.isRecursive()) return;
        if (!expanded.add(node.fullMethod())) return;
        if (node.children() != null) {
            for (var child : node.children()) {
                if (count[0] >= limit) { truncated[0] = true; break; }
                traverse(child, repoId, depth + 1, sb, count, truncated, expanded, limit, depAgg);
            }
        }
    }

    private boolean isNoise(String fullMethod) {
        if (fullMethod == null) return true;
        for (String prefix : NOISE_PACKAGE_PREFIXES) {
            if (fullMethod.startsWith(prefix)) return true;
        }
        if (fullMethod.contains("Logger:") || fullMethod.contains("LogFactory:")) return true;
        String method = fullMethod.contains(":") ? fullMethod.substring(fullMethod.indexOf(':') + 1) : fullMethod;
        // getter/setter/is 样板
        if ((method.startsWith("get") && method.length() > 3 && Character.isUpperCase(method.charAt(3)))
                || (method.startsWith("set") && method.length() > 3 && Character.isUpperCase(method.charAt(3)))
                || (method.startsWith("is") && method.length() > 2 && Character.isUpperCase(method.charAt(2)))) {
            return true;
        }
        return false;
    }

    private String oneLine(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private String shortRef(String fullMethod) {
        int colonIdx = fullMethod.lastIndexOf(':');
        String cls = colonIdx > 0 ? fullMethod.substring(0, colonIdx) : fullMethod;
        String method = colonIdx > 0 ? fullMethod.substring(colonIdx + 1) : "";
        int paren = method.indexOf('(');
        if (paren > 0) method = method.substring(0, paren);
        int dotIdx = cls.lastIndexOf('.');
        String shortCls = dotIdx >= 0 ? cls.substring(dotIdx + 1) : cls;
        return shortCls + "." + method;
    }
}
