package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.exception.ClaudeApiException;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeApiClient {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeApiClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final SystemConfigRepo configRepo;

    public ClaudeApiClient(SystemConfigRepo configRepo) {
        this.configRepo = configRepo;
    }

    public boolean isConfigured() {
        return configRepo.findByConfigKey("claude.api.key")
                .map(c -> c.getConfigValue() != null && !c.getConfigValue().isBlank())
                .orElse(false);
    }

    /**
     * 调用 Claude API
     */
    public String chat(String systemPrompt, List<Map<String, String>> messages) {
        String apiUrl = configRepo.findByConfigKey("claude.api.url")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("https://api.anthropic.com");

        String apiKey = configRepo.findByConfigKey("claude.api.key")
                .map(c -> c.getConfigValue())
                .orElse(null);

        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        try {
            // 构建请求体
            Map<String, Object> body = Map.of(
                    "model", "claude-sonnet-4-20250514",
                    "max_tokens", 4096,
                    "system", systemPrompt,
                    "messages", messages
            );

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            logger.info("调用 Claude API: {}", apiUrl);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = response.body();
                logger.error("Claude API 错误: status={}, body={}", response.statusCode(), errorMsg);

                if (response.statusCode() == 401) {
                    throw new ClaudeApiException("AUTH_FAILED", "API Key 无效", "请检查 Claude API Key 是否正确");
                } else if (response.statusCode() == 429) {
                    throw new ClaudeApiException("RATE_LIMIT", "API 调用频率超限", "请稍后再试");
                } else {
                    throw new ClaudeApiException("API_ERROR", "Claude API 错误: " + response.statusCode(), errorMsg);
                }
            }

            // 解析响应
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("content");
            if (content.isArray() && content.size() > 0) {
                return content.get(0).path("text").asText();
            }
            return "AI 未返回有效回答";

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Claude API 调用失败", e);
            throw new ClaudeApiException("NETWORK_ERROR", "网络错误: " + e.getMessage(), "请检查网络连接和 API 地址");
        }
    }

    /**
     * 流式调用 Claude API，逐 token 回调。返回完整文本。
     */
    public String chatStream(String systemPrompt, List<Map<String, String>> messages, java.util.function.Consumer<String> onToken) {
        String apiUrl = configRepo.findByConfigKey("claude.api.url")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("https://api.anthropic.com");
        String apiKey = configRepo.findByConfigKey("claude.api.key")
                .map(c -> c.getConfigValue())
                .orElse(null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", "claude-sonnet-4-20250514",
                    "max_tokens", 4096,
                    "system", systemPrompt,
                    "messages", messages,
                    "stream", true
            );
            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            logger.info("流式调用 Claude API: {}", apiUrl);
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new ClaudeApiException("API_ERROR", "Claude API 错误: " + response.statusCode(), "");
            }

            StringBuilder full = new StringBuilder();
            response.body().forEach(line -> {
                if (line == null || !line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode node = mapper.readTree(data);
                    if ("content_block_delta".equals(node.path("type").asText())) {
                        String text = node.path("delta").path("text").asText("");
                        if (!text.isEmpty()) {
                            full.append(text);
                            onToken.accept(text);
                        }
                    }
                } catch (Exception ignored) {}
            });
            return full.toString();

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Claude API 流式调用失败", e);
            throw new ClaudeApiException("NETWORK_ERROR", "网络错误: " + e.getMessage(), "请检查网络连接和 API 地址");
        }
    }

    /**
     * 支持 function calling 的 Claude 调用。
     * 每轮传入 messages（含历史 tool_result）和工具定义，返回结构化响应。
     *
     * @param systemPrompt 系统提示词
     * @param messages     对话历史（含 tool_result）
     * @param tools        工具定义列表
     * @return ToolCallResponse：含文本回答或工具调用列表
     */
    public ToolCallResponse chatWithTools(String systemPrompt,
                                          List<Map<String, Object>> messages,
                                          List<ToolDefinition> tools) {
        String apiUrl = configRepo.findByConfigKey("claude.api.url")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("https://api.anthropic.com");

        String apiKey = configRepo.findByConfigKey("claude.api.key")
                .map(c -> c.getConfigValue())
                .orElse(null);

        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", "claude-sonnet-4-20250514");
            body.put("max_tokens", 8192);
            body.put("system", systemPrompt);

            // messages
            ArrayNode msgsNode = body.putArray("messages");
            for (Map<String, Object> msg : messages) {
                msgsNode.add(mapper.convertValue(msg, ObjectNode.class));
            }

            // tools
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsNode = body.putArray("tools");
                for (ToolDefinition t : tools) {
                    ObjectNode toolNode = mapper.createObjectNode();
                    toolNode.put("name", t.name());
                    toolNode.put("description", t.description());
                    // input_schema
                    ObjectNode schema = mapper.createObjectNode();
                    schema.put("type", "object");
                    ObjectNode props = schema.putObject("properties");
                    ArrayNode required = schema.putArray("required");
                    for (ToolParam p : t.params()) {
                        ObjectNode pNode = props.putObject(p.name());
                        pNode.put("type", p.type());
                        pNode.put("description", p.description());
                        if (p.required()) required.add(p.name());
                    }
                    toolNode.set("input_schema", schema);
                    toolsNode.add(toolNode);
                }
            }

            String jsonBody = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = response.body();
                logger.error("Claude API (tools) 错误: status={}, body={}", response.statusCode(), errorMsg);
                if (response.statusCode() == 401) {
                    throw new ClaudeApiException("AUTH_FAILED", "API Key 无效", "请检查 Claude API Key 是否正确");
                }
                throw new ClaudeApiException("API_ERROR", "Claude API 错误: " + response.statusCode(), errorMsg);
            }

            JsonNode root = mapper.readTree(response.body());
            String stopReason = root.path("stop_reason").asText();
            JsonNode content = root.path("content");

            // 收集文本和工具调用
            StringBuilder textBuilder = new StringBuilder();
            List<ToolUseBlock> toolUses = new ArrayList<>();

            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    textBuilder.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    JsonNode input = block.path("input");
                    toolUses.add(new ToolUseBlock(id, name, input));
                }
            }

            return new ToolCallResponse(
                    stopReason,
                    textBuilder.toString(),
                    toolUses,
                    content  // 保留原始 content 供 messages history 使用
            );

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Claude API (tools) 调用失败", e);
            throw new ClaudeApiException("NETWORK_ERROR", "网络错误: " + e.getMessage(), "请检查网络连接和 API 地址");
        }
    }

    // ── 工具调用相关数据结构 ─────────────────────────────────────────────────

    /** 工具定义 */
    public record ToolDefinition(String name, String description, List<ToolParam> params) {}

    /** 工具参数定义 */
    public record ToolParam(String name, String type, String description, boolean required) {}

    /** Claude 返回的工具调用块 */
    public record ToolUseBlock(String id, String name, JsonNode input) {}

    /** chatWithTools 的完整返回 */
    public record ToolCallResponse(String stopReason, String text,
                                   List<ToolUseBlock> toolUses, JsonNode rawContent) {
        public boolean hasToolUse() {
            return "tool_use".equals(stopReason) || (toolUses != null && !toolUses.isEmpty());
        }
    }
}
