package com.adrninistrator.javacg2.platform.util;

import com.adrninistrator.javacg2.platform.dto.RequestChainDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cURL命令解析器
 */
public class CurlParser {

    private static final Logger logger = LoggerFactory.getLogger(CurlParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析cURL命令为RequestChainDTO
     */
    public static RequestChainDTO parseCurl(String curlCommand) {
        try {
            logger.info("[cURL解析] 开始解析cURL命令");
            
            // 清理命令（移除换行、多余空格等）
            String cleaned = cleanCurlCommand(curlCommand);
            
            // 提取各个部分
            String method = extractMethod(cleaned);
            String url = extractUrl(cleaned);
            Map<String, String> headers = extractHeaders(cleaned);
            String body = extractBody(cleaned);
            
            logger.info("[cURL解析] 提取结果: method={}, url={}, headers={}, body存在={}", 
                       method, url, headers.size(), body != null);
            
            // 构建RequestChainDTO
            RequestChainDTO dto = new RequestChainDTO();
            dto.setSessionId(System.currentTimeMillis());
            dto.setTimestamp(System.currentTimeMillis());
            dto.setUrl(url);
            dto.setUserAction("从cURL导入");
            
            // 创建单个请求
            RequestChainDTO.RequestInfo request = new RequestChainDTO.RequestInfo();
            request.setTimestamp(System.currentTimeMillis());
            request.setSeq(1);
            request.setMethod(method);
            request.setUrl(extractPath(url));
            request.setFullUrl(url);
            request.setRequestBody(body);
            
            // 设置默认值（因为从cURL无法获取响应）
            request.setResponseStatus(0);  // 表示未知
            request.setResponseBody(null);
            request.setDuration(0L);
            request.setSuccess(null);
            
            dto.setRequestChain(Collections.singletonList(request));
            dto.setDataFlow(Collections.emptyList());
            
            logger.info("[cURL解析] 解析成功");
            return dto;
            
        } catch (Exception e) {
            logger.error("[cURL解析] 解析失败", e);
            throw new IllegalArgumentException("cURL命令解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理cURL命令
     */
    private static String cleanCurlCommand(String curl) {
        return curl
            .replaceAll("\\\\\\s*\n\\s*", " ")  // 移除反斜杠续行
            .replaceAll("\n", " ")               // 移除换行
            .replaceAll("\\s+", " ")             // 多个空格合并为一个
            .trim();
    }

    /**
     * 提取HTTP方法
     */
    private static String extractMethod(String curl) {
        // 查找 -X 或 --request 参数
        Pattern pattern = Pattern.compile("(?:-X|--request)\\s+([A-Z]+)");
        Matcher matcher = pattern.matcher(curl);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 如果有 -d/--data，默认是POST
        if (curl.matches(".*(?:-d|--data|--data-raw|--data-binary).*")) {
            return "POST";
        }
        
        // 默认是GET
        return "GET";
    }

    /**
     * 提取URL
     */
    private static String extractUrl(String curl) {
        // 移除 curl 命令开头
        String cleaned = curl.replaceFirst("^curl\\s+", "");
        
        // 方法1: 查找引号包围的URL
        Pattern quotedPattern = Pattern.compile("['\"]([^'\"]*://[^'\"]+)['\"]");
        Matcher quotedMatcher = quotedPattern.matcher(cleaned);
        if (quotedMatcher.find()) {
            return quotedMatcher.group(1);
        }
        
        // 方法2: 查找不带引号的URL（第一个以http开头的词）
        Pattern urlPattern = Pattern.compile("(https?://\\S+)");
        Matcher urlMatcher = urlPattern.matcher(cleaned);
        if (urlMatcher.find()) {
            String url = urlMatcher.group(1);
            // 移除可能的尾部单引号或双引号
            url = url.replaceAll("['\"]$", "");
            return url;
        }
        
        throw new IllegalArgumentException("无法从cURL命令中提取URL");
    }

    /**
     * 提取路径（从完整URL中）
     */
    private static String extractPath(String fullUrl) {
        try {
            int schemeEnd = fullUrl.indexOf("://");
            if (schemeEnd == -1) {
                return fullUrl;
            }
            
            int pathStart = fullUrl.indexOf('/', schemeEnd + 3);
            if (pathStart == -1) {
                return "/";
            }
            
            return fullUrl.substring(pathStart);
            
        } catch (Exception e) {
            logger.warn("[cURL解析] 提取路径失败: {}", fullUrl, e);
            return fullUrl;
        }
    }

    /**
     * 提取请求头
     */
    private static Map<String, String> extractHeaders(String curl) {
        Map<String, String> headers = new HashMap<>();
        
        // 匹配 -H 或 --header 参数
        Pattern pattern = Pattern.compile("(?:-H|--header)\\s+['\"]([^:]+):\\s*([^'\"]+)['\"]");
        Matcher matcher = pattern.matcher(curl);
        
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            headers.put(key, value);
        }
        
        return headers;
    }

    /**
     * 提取请求体
     */
    private static String extractBody(String curl) {
        // 匹配 -d, --data, --data-raw, --data-binary 等参数
        Pattern pattern = Pattern.compile("(?:-d|--data|--data-raw|--data-binary)\\s+['\"](.+?)['\"](?=\\s+--|\\s*$)");
        Matcher matcher = pattern.matcher(curl);
        
        if (matcher.find()) {
            String body = matcher.group(1);
            
            // 尝试格式化JSON
            try {
                JsonNode jsonNode = objectMapper.readTree(body);
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            } catch (Exception e) {
                // 不是JSON，返回原始数据
                return body;
            }
        }
        
        return null;
    }

    /**
     * 检测输入是否为cURL命令
     */
    public static boolean isCurlCommand(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = input.trim();
        return trimmed.startsWith("curl ") || trimmed.startsWith("curl\t");
    }

    /**
     * 检测输入是否为JSON
     */
    public static boolean isJson(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = input.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
}
