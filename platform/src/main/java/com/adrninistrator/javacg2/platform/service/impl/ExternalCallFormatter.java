package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部调用（HTTP）展示装配器：把散落在 chunk 三个列里的信息拼成一条人类可读的外部调用条目，
 * 形如「【系统名】HTTP调用 `完整URL` — 用途」。
 *
 * <p>数据来源（都来自 chunks 表，无需再读源码）：
 * <ul>
 *   <li>base URL：{@code resolvedUrls} JSON {@code [{url, configKey, field}]}（@Value→配置 数据流解析，通常只到 host）</li>
 *   <li>接口 path：{@code constants} JSON {@code [{value}]} 中以 "/" 开头的字符串字面量（调用方法的 path 实参）</li>
 *   <li>用途：{@code callSummary}（方法/类注释、@Operation(summary)、@ApiOperation 等业务描述）</li>
 *   <li>系统名（尽力而为）：configKey 前缀 → URL path 首段 → host，供 AI 结合注释进一步润色成「XX系统」</li>
 * </ul>
 *
 * <p>设计为无状态 + 仅依赖 ChunkEntity，便于 getBoundaries / getConstants / ChainOutline 多处复用，
 * 保证「外部调用全部展示且信息一致」。
 */
@Component
public class ExternalCallFormatter {

    private static final Logger logger = LoggerFactory.getLogger(ExternalCallFormatter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条装配好的外部 HTTP 调用：系统名 + 完整 URL + 用途。 */
    public record ExternalCall(String system, String fullUrl, String purpose) {
        /** 渲染成「【系统名】HTTP调用 `URL` — 用途」。系统名/用途缺省则省略对应部分。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            if (system != null && !system.isBlank()) sb.append("【").append(system).append("】");
            sb.append("HTTP调用 `").append(fullUrl).append("`");
            if (purpose != null && !purpose.isBlank()) sb.append(" — ").append(purpose);
            return sb.toString();
        }
    }

    /**
     * 从 chunk 装配该方法的全部外部 HTTP 调用条目；无 URL 数据时返回空列表。
     */
    public List<ExternalCall> extract(ChunkEntity chunk) {
        if (chunk == null) return List.of();

        List<String[]> bases = parseBaseUrls(chunk.getResolvedUrls());   // [url, configKey]
        if (bases.isEmpty()) return List.of();

        List<String> paths = parsePathLiterals(chunk.getConstants());
        String purpose = cleanPurpose(chunk.getCallSummary());

        // base × path 笛卡尔组合；无 path 时只用 base
        List<ExternalCall> calls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] base : bases) {
            String baseUrl = base[0];
            String configKey = base[1];
            List<String> urls = new ArrayList<>();
            if (paths.isEmpty()) {
                urls.add(baseUrl);
            } else {
                for (String p : paths) urls.add(joinUrl(baseUrl, p));
            }
            for (String url : urls) {
                if (!seen.add(url)) continue;
                calls.add(new ExternalCall(guessSystem(configKey, url), url, purpose));
            }
        }
        return calls;
    }

    /** 便捷方法：直接渲染成多行字符串（每行一条「- ...」），无数据返回空串。 */
    public String render(ChunkEntity chunk) {
        List<ExternalCall> calls = extract(chunk);
        if (calls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ExternalCall c : calls) sb.append("- ").append(c.render()).append("\n");
        return sb.toString();
    }

    // ── 内部解析 ──────────────────────────────────────────────────────────────

    /** 解析 resolvedUrls JSON [{url, configKey, field}] → [url, configKey] 列表（跳过未配置/空）。 */
    private List<String[]> parseBaseUrls(String resolvedUrls) {
        List<String[]> out = new ArrayList<>();
        if (resolvedUrls == null || resolvedUrls.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(resolvedUrls);
            if (!arr.isArray()) return out;
            Set<String> dedup = new LinkedHashSet<>();
            for (JsonNode u : arr) {
                String url = u.path("url").asText("").trim();
                if (url.isEmpty() || url.startsWith("(未配置)") || url.startsWith("${")) continue;
                String cfgKey = u.path("configKey").isMissingNode() ? null : u.path("configKey").asText(null);
                if (dedup.add(url)) out.add(new String[]{url, cfgKey});
            }
        } catch (Exception e) {
            logger.debug("[ExternalCall] resolvedUrls 解析失败: {}", e.getMessage());
        }
        return out;
    }

    /** 解析 constants JSON [{value}] → 以 "/" 开头的 path 字面量列表（即调用时传入的接口路径）。 */
    private List<String> parsePathLiterals(String constants) {
        List<String> out = new ArrayList<>();
        if (constants == null || constants.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(constants);
            if (!arr.isArray()) return out;
            Set<String> dedup = new LinkedHashSet<>();
            for (JsonNode c : arr) {
                String v = c.path("value").asText("").trim();
                // 接口 path：以 / 开头、不含空格、长度合理；排除纯 "/" 和带占位符模板的整段
                if (v.startsWith("/") && v.length() >= 2 && !v.contains(" ") && dedup.add(v)) {
                    out.add(v);
                }
            }
        } catch (Exception e) {
            logger.debug("[ExternalCall] constants 解析失败: {}", e.getMessage());
        }
        return out;
    }

    /** callSummary 取首句作为用途（去掉多余分隔符/空白），限长。 */
    private String cleanPurpose(String callSummary) {
        if (callSummary == null || callSummary.isBlank()) return null;
        String s = callSummary.replaceAll("\\s+", " ").trim();
        // call_summary 可能是「描述 | 描述」多段，取第一段
        int bar = s.indexOf('|');
        if (bar > 0) s = s.substring(0, bar).trim();
        if (s.isEmpty()) return null;
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 拼接 base + path，规整中间斜杠。 */
    private String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    /**
     * 系统名尽力而为：configKey 前缀 → URL path 首段 → host。
     * 拿不到可读名时返回 configKey 前缀或 host，供 AI 结合注释润色成「XX系统」。
     */
    private String guessSystem(String configKey, String url) {
        // 1) 配置 key：跳过通用段，取第一个有业务含义的段。
        //    如 homework.service.url → homework；http.tutorial.url → tutorial（"http"/"url" 是通用段需跳过）。
        if (configKey != null && !configKey.isBlank()) {
            for (String seg : configKey.split("[.\\-_]")) {
                seg = seg.trim();
                if (seg.length() >= 2 && !isGenericSeg(seg)) return seg;
            }
        }
        // 2) URL path 首个业务段：http://host:port/api/homework/... → homework（跳过 api/v1 等通用段）
        int schemeIdx = url.indexOf("://");
        String afterHost = schemeIdx >= 0 ? url.substring(schemeIdx + 3) : url;
        int slash = afterHost.indexOf('/');
        String host = slash >= 0 ? afterHost.substring(0, slash) : afterHost;
        if (slash >= 0) {
            for (String seg : afterHost.substring(slash + 1).split("/")) {
                seg = seg.trim();
                if (seg.length() >= 2 && !isGenericSeg(seg) && !seg.contains("{") && !seg.matches("\\d+")) {
                    return seg;
                }
            }
        }
        // 3) 兜底：host
        return host.isEmpty() ? null : host;
    }

    /** 通用/无业务含义的 URL/配置段，不作为系统名。 */
    private boolean isGenericSeg(String seg) {
        String s = seg.toLowerCase();
        return Set.of("api", "service", "services", "url", "host", "base", "baseurl",
                "endpoint", "gateway", "rest", "http", "https", "v1", "v2", "v3",
                "open", "inner", "internal", "external", "common", "web").contains(s);
    }
}
