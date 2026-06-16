package com.adrninistrator.javacg2.platform.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从项目的 pom.xml / build.gradle 中提取框架和依赖版本信息
 */
@Component
public class ProjectInfoExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ProjectInfoExtractor.class);

    public ProjectInfo extract(String repoPath) {
        ProjectInfo info = new ProjectInfo();

        // 尝试 Maven：扫描所有 pom.xml（多模块项目的实际依赖在子模块 pom 中）
        List<Path> pomFiles = findAllFiles(repoPath, "pom.xml", 3);
        if (!pomFiles.isEmpty()) {
            info.buildTool = "Maven";
            for (Path pomFile : pomFiles) {
                parsePom(pomFile, info);
            }
        }

        // 尝试 Gradle
        Path gradleFile = findFile(repoPath, "build.gradle");
        if (gradleFile != null) {
            info.buildTool = info.buildTool != null ? info.buildTool : "Gradle";
            parseGradle(gradleFile, info);
        }

        // 检测 Java 版本
        if (info.javaVersion == null) {
            info.javaVersion = detectJavaVersion(repoPath);
        }

        logger.info("项目信息: buildTool={}, java={}, springBoot={}, 依赖数={}",
                info.buildTool, info.javaVersion, info.springBootVersion, info.dependencies.size());

        return info;
    }

    /**
     * 生成项目技术栈描述（用于 system prompt）
     */
    public String generateTechStackDescription(ProjectInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目技术栈\n");
        if (info.buildTool != null) sb.append("- 构建工具: ").append(info.buildTool).append("\n");
        if (info.javaVersion != null) sb.append("- Java: ").append(info.javaVersion).append("\n");
        if (info.springBootVersion != null) sb.append("- Spring Boot: ").append(info.springBootVersion).append("\n");

        // 关键框架
        Map<String, String> keyFrameworks = new LinkedHashMap<>();
        for (Map.Entry<String, String> dep : info.dependencies.entrySet()) {
            String key = dep.getKey().toLowerCase();
            if (key.contains("mybatis-plus")) keyFrameworks.put("MyBatis-Plus", dep.getValue());
            else if (key.contains("mybatis") && !key.contains("plus")) keyFrameworks.put("MyBatis", dep.getValue());
            else if (key.contains("spring-data-redis") || key.contains("jedis") || key.contains("lettuce")) keyFrameworks.put("Redis", dep.getValue());
            else if (key.contains("kafka")) keyFrameworks.put("Kafka", dep.getValue());
            else if (key.contains("rocketmq")) keyFrameworks.put("RocketMQ", dep.getValue());
            else if (key.contains("rabbitmq") || key.contains("amqp")) keyFrameworks.put("RabbitMQ", dep.getValue());
            else if (key.contains("grpc")) keyFrameworks.put("gRPC", dep.getValue());
            else if (key.contains("mysql")) keyFrameworks.put("MySQL", dep.getValue());
            else if (key.contains("postgresql")) keyFrameworks.put("PostgreSQL", dep.getValue());
            else if (key.contains("elasticsearch")) keyFrameworks.put("Elasticsearch", dep.getValue());
            else if (key.contains("mongodb")) keyFrameworks.put("MongoDB", dep.getValue());
            else if (key.contains("fastjson")) keyFrameworks.put("Fastjson", dep.getValue());
            else if (key.contains("jackson")) keyFrameworks.put("Jackson", dep.getValue());
            else if (key.contains("swagger") || key.contains("springdoc")) keyFrameworks.put("Swagger/OpenAPI", dep.getValue());
            else if (key.contains("feign")) keyFrameworks.put("Feign", dep.getValue());
            else if (key.contains("dubbo")) keyFrameworks.put("Dubbo", dep.getValue());
            else if (key.contains("nacos")) keyFrameworks.put("Nacos", dep.getValue());
            else if (key.contains("sentinel")) keyFrameworks.put("Sentinel", dep.getValue());
            else if (key.contains("seata")) keyFrameworks.put("Seata", dep.getValue());
        }

        for (Map.Entry<String, String> fw : keyFrameworks.entrySet()) {
            sb.append("- ").append(fw.getKey());
            if (fw.getValue() != null && !fw.getValue().isBlank()) sb.append(": ").append(fw.getValue());
            sb.append("\n");
        }

        return sb.toString();
    }

    private void parsePom(Path pomFile, ProjectInfo info) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            // 先收集所有 properties（含父 pom）
            Map<String, String> properties = new LinkedHashMap<>();
            collectProperties(pomFile, properties);

            // Java 版本 - 不覆盖已有值
            String jv = properties.getOrDefault("java.version", properties.get("maven.compiler.source"));
            if (jv != null && !jv.isBlank() && info.javaVersion == null) info.javaVersion = jv;

            // Spring Boot 版本（从 parent 或 properties）- 不覆盖已有值
            if (info.springBootVersion == null) {
                NodeList parents = doc.getElementsByTagName("parent");
                if (parents.getLength() > 0) {
                    Element parent = (Element) parents.item(0);
                    String artifactId = getChildText(parent, "artifactId");
                    if ("spring-boot-starter-parent".equals(artifactId)) {
                        info.springBootVersion = resolveProperty(getChildText(parent, "version"), properties);
                    }
                }
                if (info.springBootVersion == null) {
                    info.springBootVersion = properties.get("spring-boot.version");
                }
            }

            // 依赖：只收集 <dependencies> 下的直接依赖，跳过 <dependencyManagement> 中的版本声明
            NodeList deps = doc.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                // 检查祖先节点中是否存在 dependencyManagement，是则跳过（版本管理声明，非实际依赖）
                if (hasAncestorWithName(dep, "dependencyManagement")) continue;
                String groupId = getChildText(dep, "groupId");
                String artifactId = getChildText(dep, "artifactId");
                String version = resolveProperty(getChildText(dep, "version"), properties);
                if (artifactId != null) {
                    info.dependencies.put(
                            (groupId != null ? groupId + ":" : "") + artifactId,
                            version != null ? version : ""
                    );
                }
            }
        } catch (Exception e) {
            logger.warn("解析 pom.xml 失败: {}", pomFile, e);
        }
    }

    private void parseGradle(Path gradleFile, ProjectInfo info) {
        try {
            String content = Files.readString(gradleFile);

            // Spring Boot 版本
            Matcher sbMatcher = Pattern.compile("org\\.springframework\\.boot.*version\\s*['\"]([^'\"]+)['\"]").matcher(content);
            if (sbMatcher.find()) info.springBootVersion = sbMatcher.group(1);

            // Java 版本
            Matcher javaMatcher = Pattern.compile("sourceCompatibility\\s*=\\s*['\"]?([\\d.]+)").matcher(content);
            if (javaMatcher.find()) info.javaVersion = javaMatcher.group(1);

            // 依赖
            Matcher depMatcher = Pattern.compile("(?:implementation|api|compile)\\s+['\"]([^'\"]+)['\"]").matcher(content);
            while (depMatcher.find()) {
                String dep = depMatcher.group(1);
                String[] parts = dep.split(":");
                if (parts.length >= 2) {
                    info.dependencies.put(parts[0] + ":" + parts[1], parts.length > 2 ? parts[2] : "");
                }
            }
        } catch (IOException e) {
            logger.warn("解析 build.gradle 失败: {}", gradleFile, e);
        }
    }

    private String detectJavaVersion(String repoPath) {
        // 从 .java-version 或 JAVA_HOME 推断
        try {
            Path javaVersion = Path.of(repoPath, ".java-version");
            if (Files.exists(javaVersion)) return Files.readString(javaVersion).trim();
        } catch (IOException ignored) {}
        return null;
    }

    private Path findFile(String repoPath, String fileName) {
        Path direct = Path.of(repoPath, fileName);
        if (Files.exists(direct)) return direct;
        try (var walk = Files.walk(Path.of(repoPath), 3)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    /** 扫描项目下所有匹配文件（排除构建产物目录） */
    private List<Path> findAllFiles(String repoPath, String fileName, int maxDepth) {
        try (var walk = Files.walk(Path.of(repoPath), maxDepth)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/target/") && !s.contains("/node_modules/")
                                && !s.contains("/.git/") && !s.contains("/data/");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    /** 收集 properties：当前 pom + 父 pom（向上查找） */
    private void collectProperties(Path pomFile, Map<String, String> properties) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.toFile());
            // 先解析父 pom（向上查找）
            Path parentPom = pomFile.getParent() != null ? pomFile.getParent().getParent() : null;
            if (parentPom != null) {
                Path parentPomFile = parentPom.resolve("pom.xml");
                if (Files.exists(parentPomFile) && !parentPomFile.equals(pomFile)) {
                    collectProperties(parentPomFile, properties);
                }
            }
            // 再解析当前 pom 的 properties（覆盖父 pom）
            NodeList props = doc.getElementsByTagName("properties");
            if (props.getLength() > 0) {
                Element propsEl = (Element) props.item(0);
                var children = propsEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element) {
                        Element el = (Element) children.item(i);
                        String value = el.getTextContent().trim();
                        if (!value.isEmpty()) {
                            properties.put(el.getTagName(), value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /** 解析 ${xxx} 占位符 */
    private String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) return null;
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            return properties.getOrDefault(key, value);
        }
        return value;
    }

    /** 检查元素是否有指定名称的祖先节点 */
    private boolean hasAncestorWithName(org.w3c.dom.Node node, String ancestorName) {
        org.w3c.dom.Node parent = node.getParentNode();
        while (parent != null) {
            if (ancestorName.equals(parent.getNodeName())) return true;
            parent = parent.getParentNode();
        }
        return false;
    }

    private String getXmlText(Document doc, String tagName) {
        // 从 properties 中查找
        NodeList props = doc.getElementsByTagName("properties");
        if (props.getLength() > 0) {
            Element propsEl = (Element) props.item(0);
            NodeList children = propsEl.getElementsByTagName(tagName);
            if (children.getLength() > 0) return children.item(0).getTextContent().trim();
        }
        return null;
    }

    private String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) return children.item(0).getTextContent().trim();
        return null;
    }

    public static class ProjectInfo {
        public String buildTool;
        public String javaVersion;
        public String springBootVersion;
        public Map<String, String> dependencies = new LinkedHashMap<>();
    }
}
