package com.adrninistrator.javacg2.platform.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 配置文件解析工具
 * 从 Spring Boot 项目的 application.yml/properties 中提取配置
 */
public class ConfigFileParser {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigFileParser.class);
    
    /**
     * 从仓库路径中读取配置文件
     * @param repoLocalPath 仓库本地路径
     * @param profile 配置文件 profile（如 dev, prod），可为 null
     * @return 配置 Map
     */
    public static Map<String, String> parseConfig(String repoLocalPath, String profile) {
        Map<String, String> config = new LinkedHashMap<>();
        
        if (repoLocalPath == null || repoLocalPath.isBlank()) {
            return config;
        }
        
        try {
            // 查找配置文件
            List<File> configFiles = findConfigFiles(repoLocalPath, profile);
            
            for (File file : configFiles) {
                if (file.getName().endsWith(".yml") || file.getName().endsWith(".yaml")) {
                    config.putAll(parseYamlFile(file));
                } else if (file.getName().endsWith(".properties")) {
                    config.putAll(parsePropertiesFile(file));
                }
            }
            
            logger.debug("[配置解析] 从 {} 读取了 {} 个配置项", repoLocalPath, config.size());
            
        } catch (Exception e) {
            logger.warn("[配置解析] 失败: {}", repoLocalPath, e);
        }
        
        return config;
    }
    
    /**
     * 查找配置文件
     * 优先级：application-{profile}.yml > application.yml > application.properties
     */
    private static List<File> findConfigFiles(String repoPath, String profile) {
        List<File> files = new ArrayList<>();
        
        // 常见的配置文件路径
        String[] possiblePaths = {
            "src/main/resources",
            "src/resources",
            "resources",
            ""
        };
        
        for (String path : possiblePaths) {
            Path configDir = Paths.get(repoPath, path);
            if (!Files.isDirectory(configDir)) {
                continue;
            }
            
            // 优先读取 profile 特定的配置
            if (profile != null && !profile.isBlank()) {
                addIfExists(files, configDir, "application-" + profile + ".yml");
                addIfExists(files, configDir, "application-" + profile + ".yaml");
                addIfExists(files, configDir, "application-" + profile + ".properties");
            }
            
            // 读取默认配置
            addIfExists(files, configDir, "application.yml");
            addIfExists(files, configDir, "application.yaml");
            addIfExists(files, configDir, "application.properties");
            
            if (!files.isEmpty()) {
                break;  // 找到配置文件就停止
            }
        }
        
        return files;
    }
    
    private static void addIfExists(List<File> files, Path dir, String filename) {
        File file = dir.resolve(filename).toFile();
        if (file.exists() && file.isFile()) {
            files.add(file);
        }
    }
    
    /**
     * 解析 YAML 配置文件
     */
    private static Map<String, String> parseYamlFile(File file) {
        Map<String, String> flatConfig = new LinkedHashMap<>();
        
        try (FileInputStream fis = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(fis);
            
            if (yamlData != null) {
                flattenMap("", yamlData, flatConfig);
            }
            
        } catch (Exception e) {
            logger.warn("[YAML解析] 失败: {}", file.getAbsolutePath(), e);
        }
        
        return flatConfig;
    }
    
    /**
     * 将嵌套的 Map 扁平化为 key.subkey 形式
     */
    @SuppressWarnings("unchecked")
    private static void flattenMap(String prefix, Map<String, Object> map, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                flattenMap(key, (Map<String, Object>) value, result);
            } else if (value != null) {
                result.put(key, value.toString());
            }
        }
    }
    
    /**
     * 解析 Properties 配置文件
     */
    private static Map<String, String> parsePropertiesFile(File file) {
        Map<String, String> config = new LinkedHashMap<>();
        
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            }
            
            for (String key : props.stringPropertyNames()) {
                config.put(key, props.getProperty(key));
            }
            
        } catch (Exception e) {
            logger.warn("[Properties解析] 失败: {}", file.getAbsolutePath(), e);
        }
        
        return config;
    }
    
    /**
     * 提取 HTTP 客户端配置（RestTemplate、Feign、OkHttp 等）
     */
    public static Map<String, String> extractHttpClientConfig(Map<String, String> allConfig) {
        Map<String, String> httpConfig = new LinkedHashMap<>();
        
        // 提取相关配置
        for (Map.Entry<String, String> entry : allConfig.entrySet()) {
            String key = entry.getKey();
            
            // RestTemplate / WebClient 配置
            if (key.startsWith("spring.http.") || 
                key.startsWith("resttemplate.") ||
                key.startsWith("webclient.") ||
                key.contains("rest.client.")) {
                httpConfig.put(key, entry.getValue());
            }
            
            // Feign 配置
            if (key.startsWith("feign.") || key.contains(".feign.")) {
                httpConfig.put(key, entry.getValue());
            }
            
            // 自定义 HTTP 配置（通常以服务名开头）
            if (key.endsWith(".url") || key.endsWith(".host") || 
                key.endsWith(".port") || key.endsWith(".base-url")) {
                httpConfig.put(key, entry.getValue());
            }
        }
        
        return httpConfig;
    }
    
    /**
     * 提取数据库配置
     */
    public static Map<String, String> extractDatabaseConfig(Map<String, String> allConfig) {
        Map<String, String> dbConfig = new LinkedHashMap<>();
        
        for (Map.Entry<String, String> entry : allConfig.entrySet()) {
            String key = entry.getKey();
            
            if (key.startsWith("spring.datasource.") ||
                key.startsWith("spring.jpa.") ||
                key.startsWith("mybatis.") ||
                key.startsWith("spring.data.")) {
                dbConfig.put(key, entry.getValue());
            }
        }
        
        return dbConfig;
    }
    
    /**
     * 提取 Redis 配置
     */
    public static Map<String, String> extractRedisConfig(Map<String, String> allConfig) {
        Map<String, String> redisConfig = new LinkedHashMap<>();
        
        for (Map.Entry<String, String> entry : allConfig.entrySet()) {
            String key = entry.getKey();
            
            if (key.startsWith("spring.redis.") || key.startsWith("spring.cache.")) {
                redisConfig.put(key, entry.getValue());
            }
        }
        
        return redisConfig;
    }
    
    /**
     * 构建完整的 HTTP URL
     * @param config 配置 Map
     * @param serviceName 服务名（可选）
     * @return 完整 URL
     */
    public static String buildHttpUrl(Map<String, String> config, String serviceName) {
        // 尝试不同的配置 key 模式
        String[] patterns = {
            serviceName + ".url",
            serviceName + ".base-url",
            serviceName + ".host",
            "spring.application.url"
        };
        
        for (String pattern : patterns) {
            String url = config.get(pattern);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        
        // 尝试组合 host + port
        String host = config.get(serviceName + ".host");
        String port = config.get(serviceName + ".port");
        
        if (host != null && !host.isBlank()) {
            String protocol = "http";
            if (host.startsWith("https://") || host.startsWith("http://")) {
                return host + (port != null ? ":" + port : "");
            }
            return protocol + "://" + host + (port != null ? ":" + port : "");
        }
        
        return null;
    }
}
