package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.CallGraphRepo;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BoundaryDetectorImpl {

    private static final Logger logger = LoggerFactory.getLogger(BoundaryDetectorImpl.class);

    private final CallGraphRepo callGraphRepo;
    private final BoundaryRepo boundaryRepo;
    private final RepositoryRepo repositoryRepo;
    private final ConfigValueExtractor configExtractor;

    // 被调用方法类名前缀 -> [边界类型, 描述]
    private static final List<String[]> CALLEE_RULES = List.of(
            // HTTP
            new String[]{"org.springframework.web.client.RestTemplate", "HTTP", "RestTemplate"},
            new String[]{"org.springframework.web.reactive.function.client.WebClient", "HTTP", "WebClient"},
            new String[]{"org.apache.http.client", "HTTP", "Apache HttpClient"},
            new String[]{"org.apache.hc.client5", "HTTP", "Apache HttpClient5"},
            new String[]{"java.net.HttpURLConnection", "HTTP", "HttpURLConnection"},
            new String[]{"java.net.http.HttpClient", "HTTP", "Java HttpClient"},
            new String[]{"okhttp3.", "HTTP", "OkHttp"},
            new String[]{"feign.", "HTTP", "Feign"},
            new String[]{"org.springframework.cloud.openfeign", "HTTP", "OpenFeign"},
            // gRPC
            new String[]{"io.grpc.", "GRPC", "gRPC"},
            // MQ
            new String[]{"org.springframework.kafka.core.KafkaTemplate", "MQ", "Kafka"},
            new String[]{"org.apache.kafka.clients.producer", "MQ", "Kafka Producer"},
            new String[]{"org.apache.rocketmq.client.producer", "MQ", "RocketMQ"},
            new String[]{"org.springframework.amqp.rabbit", "MQ", "RabbitMQ"},
            // DB
            new String[]{"java.sql.", "DB", "JDBC"},
            new String[]{"javax.sql.", "DB", "JDBC DataSource"},
            new String[]{"org.springframework.jdbc", "DB", "JdbcTemplate"},
            new String[]{"org.apache.ibatis", "DB", "MyBatis"},
            new String[]{"org.mybatis", "DB", "MyBatis"},
            new String[]{"com.baomidou.mybatisplus", "DB", "MyBatis-Plus"},
            // Cache
            new String[]{"org.springframework.data.redis", "CACHE", "Redis"},
            new String[]{"redis.clients.jedis", "CACHE", "Jedis"},
            new String[]{"io.lettuce", "CACHE", "Lettuce"},
            new String[]{"org.redisson", "CACHE", "Redisson"},
            // Serialization
            new String[]{"com.fasterxml.jackson", "SERIALIZATION", "Jackson"},
            new String[]{"com.alibaba.fastjson", "SERIALIZATION", "Fastjson"},
            new String[]{"com.google.gson", "SERIALIZATION", "Gson"},
            // Transaction
            new String[]{"org.springframework.transaction", "TRANSACTION", "Spring 事务"}
    );

    // 配置关键词
    private static final Map<String, String[]> CONFIG_KEYWORDS = Map.of(
            "HTTP", new String[]{"url", "host", "port", "path", "base-url", "baseurl", "endpoint", "feign", "service-url"},
            "GRPC", new String[]{"grpc", "host", "port", "target", "channel"},
            "MQ", new String[]{"kafka", "rocketmq", "rabbitmq", "topic", "broker", "bootstrap"},
            "DB", new String[]{"datasource", "jdbc", "url", "driver", "mybatis", "database"},
            "CACHE", new String[]{"redis", "cache", "jedis", "lettuce", "redisson"}
    );

    // 从源码中提取 URL 的正则
    private static final Pattern URL_IN_SOURCE = Pattern.compile(
            "\"(https?://[^\"]+)\"|" +                              // 硬编码 URL
            "\\$\\{([^}]+)\\}|" +                                   // ${key} 或 ${key:default}
            "getProperty\\s*\\(\\s*\"([^\"]+)\"\\s*\\)"             // getProperty("xxx")
    );

    public BoundaryDetectorImpl(CallGraphRepo callGraphRepo, BoundaryRepo boundaryRepo,
                                RepositoryRepo repositoryRepo, ConfigValueExtractor configExtractor) {
        this.callGraphRepo = callGraphRepo;
        this.boundaryRepo = boundaryRepo;
        this.repositoryRepo = repositoryRepo;
        this.configExtractor = configExtractor;
    }

    public int detectBoundaries(Long repoId, String repoPath) {
        // 提取配置并保存到 repo_config 表
        Map<String, String> allConfigs = configExtractor.extractAndSave(repoId, repoPath);
        logger.info("从配置文件提取 {} 项配置", allConfigs.size());

        // 读取用户包前缀（仓库级，支持多个逗号分隔）
        String packagePrefixRaw = configExtractor.getEffectiveValue(repoId, "analyze.package.prefix", null);
        List<String> packagePrefixes = new ArrayList<>();
        if (packagePrefixRaw != null) {
            for (String p : packagePrefixRaw.split("[,;\\s]+")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) packagePrefixes.add(trimmed);
            }
        }

        // 按边界类型预查相关配置
        Map<String, Map<String, String>> configsByType = new HashMap<>();
        for (Map.Entry<String, String[]> entry : CONFIG_KEYWORDS.entrySet()) {
            Map<String, String> related = configExtractor.findRelatedConfigs(allConfigs, entry.getValue());
            if (!related.isEmpty()) configsByType.put(entry.getKey(), related);
        }

        // 只加载用户代码的调用关系（caller 必须是用户包），大幅减少数据量
        List<CallGraphEntity> allCalls = callGraphRepo.findByRepoId(repoId).stream()
                .filter(c -> c.getEnabled() != null && c.getEnabled())
                .filter(c -> !"EXTENDS".equals(c.getCallType()) && !"IMPLEMENTS".equals(c.getCallType()))
                .filter(c -> packagePrefixes.isEmpty() || packagePrefixes.stream().anyMatch(p -> extractClassName(c.getCallerMethod()).startsWith(p)))
                .collect(Collectors.toList());

        logger.info("过滤后调用关系: {} 条 (包前缀: {})", allCalls.size(), packagePrefixes);

        Map<String, List<CallGraphEntity>> callerToCallees = allCalls.stream()
                .collect(Collectors.groupingBy(CallGraphEntity::getCallerMethod));

        // 第一轮：直接匹配 — 调用方是用户代码，被调用方是框架/中间件
        Map<String, Set<String>> methodBoundaryTypes = new HashMap<>();
        Map<String, List<String>> methodBoundaryContexts = new HashMap<>();
        List<BoundaryEntity> batchBoundaries = new ArrayList<>();
        // 去重：同一个 caller+callee+type 只记一次
        Set<String> dedup = new HashSet<>();
        int count = 0;

        // 源码缓存，避免重复读磁盘
        Map<String, String> sourceCache = new HashMap<>();

        for (CallGraphEntity call : allCalls) {
            String calleeClass = extractClassName(call.getCalleeMethod());
            String calleeMethodName = extractMethodName(call.getCalleeMethod());

            for (String[] rule : CALLEE_RULES) {
                if (calleeClass.startsWith(rule[0])) {
                    String boundaryType = rule[1];
                    String callerMethod = call.getCallerMethod();

                    // 构建上下文
                    StringBuilder ctx = new StringBuilder();
                    ctx.append(rule[2]).append(": ").append(calleeClass).append(".").append(calleeMethodName);

                    // 只对 HTTP/GRPC 提取 URL（最耗时的操作）
                    if ("HTTP".equals(boundaryType) || "GRPC".equals(boundaryType)) {
                        String urlFromSource = extractUrlFromSourceCached(repoId, repoPath, callerMethod, allConfigs, sourceCache);
                        if (urlFromSource != null) {
                            ctx.append("\n📌 URL: ").append(urlFromSource);
                        }
                    }

                    // 附加配置
                    Map<String, String> relatedConfigs = configsByType.get(boundaryType);
                    if (relatedConfigs != null && !relatedConfigs.isEmpty()) {
                        ctx.append("\n--- 相关配置 ---");
                        relatedConfigs.entrySet().stream().limit(8).forEach(cfg ->
                                ctx.append("\n").append(cfg.getKey()).append(" = ").append(cfg.getValue()));
                    }

                    BoundaryEntity boundary = new BoundaryEntity();
                    boundary.setRepoId(repoId);
                    boundary.setFullMethod(callerMethod);
                    boundary.setBoundaryType(boundaryType);
                    boundary.setLineNumber(call.getLineNumber());
                    boundary.setCalleeMethod(call.getCalleeMethod());
                    boundary.setContext(ctx.toString());

                    String dedupKey = callerMethod + "|" + boundaryType + "|" + calleeClass;
                    if (dedup.add(dedupKey)) {
                        batchBoundaries.add(boundary);
                        count++;
                        methodBoundaryTypes.computeIfAbsent(callerMethod, k -> new HashSet<>()).add(boundaryType);
                        methodBoundaryContexts.computeIfAbsent(callerMethod, k -> new ArrayList<>()).add(ctx.toString());
                    }
                    break;
                }
            }

            // Feign Client 接口检测
            if (calleeClass.endsWith("Client") || calleeClass.endsWith("FeignClient")) {
                String callerMethod = call.getCallerMethod();
                if (!methodBoundaryTypes.getOrDefault(callerMethod, Set.of()).contains("HTTP")) {
                    String ctx = "Feign 远程调用: " + calleeClass + "." + calleeMethodName;
                    String urlFromSource = extractUrlFromSourceCached(repoId, repoPath, callerMethod, allConfigs, sourceCache);
                    if (urlFromSource != null) ctx += "\n📌 URL: " + urlFromSource;

                    BoundaryEntity boundary = new BoundaryEntity();
                    boundary.setRepoId(repoId);
                    boundary.setFullMethod(callerMethod);
                    boundary.setBoundaryType("HTTP");
                    boundary.setLineNumber(call.getLineNumber());
                    boundary.setCalleeMethod(call.getCalleeMethod());
                    boundary.setContext(ctx);
                    String dedupKey2 = callerMethod + "|HTTP|" + calleeClass;
                    if (dedup.add(dedupKey2)) {
                        batchBoundaries.add(boundary);
                        count++;
                        methodBoundaryTypes.computeIfAbsent(callerMethod, k -> new HashSet<>()).add("HTTP");
                    }
                }
            }
        }

        // 第二轮：向上传播 — 如果方法 A 调用了方法 B，B 有 HTTP 边界，则 A 也标记（间接调用）
        int propagated = propagateBoundaries(repoId, callerToCallees, methodBoundaryTypes, methodBoundaryContexts);
        count += propagated;

        // 批量保存所有边界点
        if (!batchBoundaries.isEmpty()) {
            boundaryRepo.saveAll(batchBoundaries);
        }

        logger.info("边界点检测完成: repoId={}, 直接 {} + 传播 {} = 共 {} 个", repoId, count - propagated, propagated, count);
        return count;
    }

    /**
     * 向上传播边界标记：如果方法的直接子调用有边界，给该方法也加一个间接边界标记
     */
    private int propagateBoundaries(Long repoId,
                                     Map<String, List<CallGraphEntity>> callerToCallees,
                                     Map<String, Set<String>> methodBoundaryTypes,
                                     Map<String, List<String>> methodBoundaryContexts) {
        List<BoundaryEntity> propagatedBoundaries = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String method : callerToCallees.keySet()) {
            if (methodBoundaryTypes.containsKey(method)) continue;
            Set<String> childBoundaries = collectChildBoundaries(method, callerToCallees, methodBoundaryTypes, visited, 0);
            if (!childBoundaries.isEmpty()) {
                for (String type : childBoundaries) {
                    BoundaryEntity boundary = new BoundaryEntity();
                    boundary.setRepoId(repoId);
                    boundary.setFullMethod(method);
                    boundary.setBoundaryType(type);
                    boundary.setContext("↘ 间接调用包含 " + type + " 操作");
                    propagatedBoundaries.add(boundary);
                }
            }
        }
        if (!propagatedBoundaries.isEmpty()) {
            boundaryRepo.saveAll(propagatedBoundaries);
        }
        return propagatedBoundaries.size();
    }

    private Set<String> collectChildBoundaries(String method,
                                                Map<String, List<CallGraphEntity>> callerToCallees,
                                                Map<String, Set<String>> methodBoundaryTypes,
                                                Set<String> visited, int depth) {
        if (depth > 5 || visited.contains(method)) return Set.of();
        visited.add(method);

        Set<String> result = new HashSet<>();
        // 自身有边界
        Set<String> own = methodBoundaryTypes.get(method);
        if (own != null) result.addAll(own);

        // 子调用
        List<CallGraphEntity> children = callerToCallees.getOrDefault(method, List.of());
        for (CallGraphEntity child : children) {
            result.addAll(collectChildBoundaries(child.getCalleeMethod(), callerToCallees, methodBoundaryTypes, visited, depth + 1));
        }
        return result;
    }

    /**
     * 从调用方的源码中提取 URL 信息
     */
    /**
     * 带缓存的 URL 提取
     */
    private String extractUrlFromSourceCached(Long repoId, String repoPath, String callerMethod,
                                               Map<String, String> allConfigs, Map<String, String> sourceCache) {
        String className = extractClassName(callerMethod);
        String topLevelClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;

        // 缓存 key 用类名
        String source = sourceCache.get(topLevelClass);
        if (source == null) {
            String relativePath = topLevelClass.replace('.', '/') + ".java";
            Path sourceFile = findSourceFile(Path.of(repoPath), relativePath);
            if (sourceFile == null) {
                sourceCache.put(topLevelClass, ""); // 标记为找不到
                return null;
            }
            try {
                source = Files.readString(sourceFile);
                sourceCache.put(topLevelClass, source);
            } catch (IOException e) {
                sourceCache.put(topLevelClass, "");
                return null;
            }
        }

        if (source.isEmpty()) return null;

        List<String> urls = new ArrayList<>();
        Matcher m = URL_IN_SOURCE.matcher(source);
        while (m.find()) {
            if (m.group(1) != null) {
                urls.add(m.group(1));
            } else if (m.group(2) != null) {
                String resolved = resolveSpringPlaceholder(repoId, m.group(2));
                if (resolved != null) urls.add(resolved);
            } else if (m.group(3) != null) {
                String resolved = resolveSpringPlaceholder(repoId, m.group(3));
                if (resolved != null) urls.add(resolved);
            }
        }

        if (!urls.isEmpty()) {
            return urls.stream().limit(3).collect(Collectors.joining(" | "));
        }
        return null;
    }

    private String extractUrlFromSource(Long repoId, String repoPath, String callerMethod, Map<String, String> allConfigs) {
        String className = extractClassName(callerMethod);
        String topLevelClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relativePath = topLevelClass.replace('.', '/') + ".java";

        // 搜索源码文件
        Path sourceFile = findSourceFile(Path.of(repoPath), relativePath);
        if (sourceFile == null) return null;

        try {
            String source = Files.readString(sourceFile);
            List<String> urls = new ArrayList<>();

            Matcher m = URL_IN_SOURCE.matcher(source);
            while (m.find()) {
                if (m.group(1) != null) {
                    // 硬编码 URL
                    urls.add(m.group(1));
                } else if (m.group(2) != null) {
                    // ${key} 或 ${key:default} → 从 repo_config 取有效值
                    String resolved = resolveSpringPlaceholder(repoId, m.group(2));
                    if (resolved != null) urls.add(resolved);
                } else if (m.group(3) != null) {
                    // getProperty("xxx")
                    String resolved = resolveSpringPlaceholder(repoId, m.group(3));
                    if (resolved != null) urls.add(resolved);
                }
            }

            if (!urls.isEmpty()) {
                return String.join(" | ", urls.stream().limit(3).collect(Collectors.toList()));
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    /**
     * 解析 Spring 占位符 ${key:default}
     * 优先从配置文件取值，取不到用默认值，都没有返回 key 名
     */
    private String resolveSpringPlaceholder(Long repoId, String placeholder) {
        String key;
        String defaultValue = null;
        int colonIdx = placeholder.indexOf(':');
        if (colonIdx > 0) {
            key = placeholder.substring(0, colonIdx).trim();
            defaultValue = placeholder.substring(colonIdx + 1).trim();
        } else {
            key = placeholder.trim();
        }

        String value = configExtractor.getEffectiveValue(repoId, key, defaultValue);
        return value != null ? value : "${" + key + "} (未配置)";
    }

    private Path findSourceFile(Path repoRoot, String relativePath) {
        Path direct = repoRoot.resolve("src/main/java").resolve(relativePath);
        if (Files.exists(direct)) return direct;
        try (var walk = Files.walk(repoRoot, 8)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("java")
                            && p.getParent() != null && "main".equals(p.getParent().getFileName().toString())
                            && p.getParent().getParent() != null && "src".equals(p.getParent().getParent().getFileName().toString())
                            && Files.isDirectory(p))
                    .map(srcDir -> srcDir.resolve(relativePath))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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
}
