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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeApiClient {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeApiClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 每次请求新建 HttpClient，避免连接池中陈旧连接复用导致的 "no bytes" 超时问题 */
    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

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

        String model = configRepo.findByConfigKey("claude.model")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("claude-sonnet-4-20250514");

        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        // 非官方 anthropic.com 地址，走 OpenAI 兼容格式（代理/中转通常都支持此格式）
        boolean isOpenAiCompat = !apiUrl.contains("anthropic.com");

        try {
            String jsonBody;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120));

            if (isOpenAiCompat) {
                // OpenAI 兼容格式：/v1/chat/completions
                List<Map<String, String>> fullMessages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    fullMessages.add(Map.of("role", "system", "content", systemPrompt));
                }
                fullMessages.addAll(messages);

                Map<String, Object> body = Map.of(
                        "model", model,
                        "max_tokens", 4096,
                        "messages", fullMessages
                );
                jsonBody = mapper.writeValueAsString(body);

                // 去掉末尾斜杠后拼路径，避免双斜杠
                String baseUrl = apiUrl.replaceAll("/+$", "");
                reqBuilder.uri(URI.create(baseUrl + "/v1/chat/completions"))
                          .header("Authorization", "Bearer " + apiKey);
            } else {
                // Claude 原生格式：/v1/messages
                Map<String, Object> body = Map.of(
                        "model", model,
                        "max_tokens", 4096,
                        "system", systemPrompt,
                        "messages", messages
                );
                jsonBody = mapper.writeValueAsString(body);

                String baseUrl = apiUrl.replaceAll("/+$", "");
                reqBuilder.uri(URI.create(baseUrl + "/v1/messages"))
                          .header("x-api-key", apiKey)
                          .header("anthropic-version", "2023-06-01");
            }

            HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            logger.info("调用 AI API: {} ({}格式)", apiUrl, isOpenAiCompat ? "OpenAI兼容" : "Claude原生");
            HttpClient client = newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = response.body();
                logger.error("AI API 错误: status={}, body={}", response.statusCode(), errorMsg);
                if (response.statusCode() == 401) {
                    throw new ClaudeApiException("AUTH_FAILED", "API Key 无效", "请检查 API Key 是否正确");
                } else if (response.statusCode() == 429) {
                    throw new ClaudeApiException("RATE_LIMIT", "API 调用频率超限", "请稍后再试");
                } else {
                    throw new ClaudeApiException("API_ERROR", "API 错误: " + response.statusCode(), errorMsg);
                }
            }

            // 解析响应（兼容两种格式）
            JsonNode root = mapper.readTree(response.body());
            if (isOpenAiCompat) {
                // OpenAI: choices[0].message.content
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    return choices.get(0).path("message").path("content").asText();
                }
            } else {
                // Claude: content[0].text
                JsonNode content = root.path("content");
                if (content.isArray() && content.size() > 0) {
                    return content.get(0).path("text").asText();
                }
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
     * 流式调用 AI API，逐 token 回调。自动识别 Claude 原生 / OpenAI 兼容格式。
     * 使用 HttpURLConnection（而非 java.net.http.HttpClient）以获得对 HTTP/1.1 代理更好的兼容性：
     * - 支持显式设置 Connection: close，防止连接复用导致的 "no bytes" 超时
     * - setReadTimeout 提供精确的读超时控制
     */
    public String chatStream(String systemPrompt, List<Map<String, String>> messages, java.util.function.Consumer<String> onToken) {
        String apiUrl = configRepo.findByConfigKey("claude.api.url")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("https://api.anthropic.com");
        String apiKey = configRepo.findByConfigKey("claude.api.key")
                .map(c -> c.getConfigValue())
                .orElse(null);
        String model = configRepo.findByConfigKey("claude.model")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("claude-sonnet-4-20250514");

        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        boolean isOpenAiCompat = !apiUrl.contains("anthropic.com");
        String baseUrl = apiUrl.replaceAll("/+$", "");

        try {
            // 构建请求体
            String jsonBody;
            String endpoint;

            if (isOpenAiCompat) {
                List<Map<String, String>> fullMessages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    fullMessages.add(Map.of("role", "system", "content", systemPrompt));
                }
                fullMessages.addAll(messages);
                Map<String, Object> body = Map.of(
                        "model", model, "max_tokens", 4096,
                        "messages", fullMessages, "stream", true);
                jsonBody = mapper.writeValueAsString(body);
                endpoint = baseUrl + "/v1/chat/completions";
            } else {
                Map<String, Object> body = Map.of(
                        "model", model, "max_tokens", 4096,
                        "system", systemPrompt, "messages", messages, "stream", true);
                jsonBody = mapper.writeValueAsString(body);
                endpoint = baseUrl + "/v1/messages";
            }

            logger.info("流式调用 AI API: {} ({}格式)", apiUrl, isOpenAiCompat ? "OpenAI兼容" : "Claude原生");

            // 使用 HttpURLConnection：允许 Connection:close，有独立 readTimeout，对 HTTP/1.1 代理兼容性最佳
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setConnectTimeout(30_000);   // 30s 建连超时
            conn.setReadTimeout(180_000);      // 180s 读超时（模型生成可能较慢）
            conn.setDoOutput(true);

            if (isOpenAiCompat) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            } else {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            }

            // 发送请求体
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                String errBody = "";
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                logger.error("流式 AI API 错误: status={}, body={}", statusCode, errBody);
                throw new ClaudeApiException("API_ERROR", "API 错误: " + statusCode, errBody);
            }

            // 逐行消费 SSE
            // 缓冲区设为 1，防止小 chunk 被 BufferedReader 内部缓冲积压导致 token 不实时推送
            StringBuilder full = new StringBuilder();
            int tokenCount = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), 1)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        JsonNode node = mapper.readTree(data);
                        String text = "";
                        if (isOpenAiCompat) {
                            text = node.path("choices").path(0).path("delta").path("content").asText("");
                        } else {
                            if ("content_block_delta".equals(node.path("type").asText())) {
                                text = node.path("delta").path("text").asText("");
                            }
                        }
                        if (!text.isEmpty()) {
                            if (tokenCount == 0) logger.info("[chatStream] 收到第一个 token");
                            tokenCount++;
                            full.append(text);
                            if (onToken != null) onToken.accept(text);
                        }
                    } catch (Exception ignored) {}
                }
            } finally {
                conn.disconnect();
            }
            logger.info("[chatStream] 流式结束，共 {} 个 token，总长度 {} 字符", tokenCount, full.length());

            return full.toString();

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Claude API 流式调用失败", e);
            throw new ClaudeApiException("NETWORK_ERROR", "网络错误: " + e.getMessage(), "请检查网络连接和 API 地址");
        }
    }

    /**
     * 支持 function calling 的调用。自动识别 Claude 原生 / OpenAI 兼容格式。
     * 每轮传入 messages（含历史 tool_result，Claude 格式）和工具定义，返回结构化响应。
     * 对于 OpenAI 兼容端点，内部完成格式双向转换，调用方无需感知。
     *
     * @param systemPrompt 系统提示词
     * @param messages     对话历史（含 tool_result，Claude 原生格式）
     * @param tools        工具定义列表
     * @return ToolCallResponse：含文本回答或工具调用列表（rawContent 始终为 Claude 格式）
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

        String model = configRepo.findByConfigKey("claude.model")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse("claude-sonnet-4-20250514");

        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException("NOT_CONFIGURED", "请先在系统配置中设置 Claude API Key", "进入系统配置页面");
        }

        boolean isOpenAiCompat = !apiUrl.contains("anthropic.com");
        String baseUrl = apiUrl.replaceAll("/+$", "");

        try {
            String jsonBody;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120));

            if (isOpenAiCompat) {
                // ── OpenAI 兼容格式 (/v1/chat/completions) ────────────────────
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);
                body.put("max_tokens", 8192);

                // 将 Claude 格式 messages 转为 OpenAI 格式
                ArrayNode msgsNode = body.putArray("messages");
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    ObjectNode sysMsg = msgsNode.addObject();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", systemPrompt);
                }
                for (Map<String, Object> msg : messages) {
                    convertClaudeMessageToOpenAi(msg, msgsNode);
                }

                // 将 ToolDefinition 转为 OpenAI tools 格式
                if (tools != null && !tools.isEmpty()) {
                    ArrayNode toolsNode = body.putArray("tools");
                    for (ToolDefinition t : tools) {
                        ObjectNode toolNode = toolsNode.addObject();
                        toolNode.put("type", "function");
                        ObjectNode fn = toolNode.putObject("function");
                        fn.put("name", t.name());
                        fn.put("description", t.description());
                        ObjectNode params = fn.putObject("parameters");
                        params.put("type", "object");
                        ObjectNode props = params.putObject("properties");
                        ArrayNode required = params.putArray("required");
                        for (ToolParam p : t.params()) {
                            ObjectNode pNode = props.putObject(p.name());
                            pNode.put("type", p.type());
                            pNode.put("description", p.description());
                            if (p.required()) required.add(p.name());
                        }
                    }
                }

                jsonBody = mapper.writeValueAsString(body);
                reqBuilder.uri(URI.create(baseUrl + "/v1/chat/completions"))
                          .header("Authorization", "Bearer " + apiKey);

            } else {
                // ── Claude 原生格式 (/v1/messages) ────────────────────────────
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);
                body.put("max_tokens", 8192);
                body.put("system", systemPrompt);

                ArrayNode msgsNode = body.putArray("messages");
                for (Map<String, Object> msg : messages) {
                    msgsNode.add(mapper.convertValue(msg, ObjectNode.class));
                }

                if (tools != null && !tools.isEmpty()) {
                    ArrayNode toolsNode = body.putArray("tools");
                    for (ToolDefinition t : tools) {
                        ObjectNode toolNode = mapper.createObjectNode();
                        toolNode.put("name", t.name());
                        toolNode.put("description", t.description());
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

                jsonBody = mapper.writeValueAsString(body);
                reqBuilder.uri(URI.create(baseUrl + "/v1/messages"))
                          .header("x-api-key", apiKey)
                          .header("anthropic-version", "2023-06-01");
            }

            HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            logger.info("调用 AI API (tools): {} ({}格式)", apiUrl, isOpenAiCompat ? "OpenAI兼容" : "Claude原生");

            // 429 指数退避重试：最多 3 次，等待 5s / 10s / 20s
            int maxRetries = 3;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                HttpClient client = newHttpClient();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    if (attempt < maxRetries) {
                        long waitMs = (long) Math.pow(2, attempt + 1) * 5000L; // 5s, 10s, 20s
                        logger.warn("Claude API (tools) 429 限流，第 {}/{} 次重试，等待 {}ms", attempt + 1, maxRetries, waitMs);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    String errorMsg = response.body();
                    logger.error("Claude API (tools) 429 重试耗尽: {}", errorMsg);
                    throw new ClaudeApiException("API_ERROR", "API 错误: 429", errorMsg);
                }

                if (response.statusCode() != 200) {
                    String errorMsg = response.body();
                    logger.error("Claude API (tools) 错误: status={}, body={}", response.statusCode(), errorMsg);
                    if (response.statusCode() == 401) {
                        throw new ClaudeApiException("AUTH_FAILED", "API Key 无效", "请检查 API Key 是否正确");
                    }
                    throw new ClaudeApiException("API_ERROR", "API 错误: " + response.statusCode(), errorMsg);
                }

                JsonNode root = mapper.readTree(response.body());
                if (isOpenAiCompat) {
                    return parseOpenAiToolsResponse(root);
                } else {
                    return parseClaudeToolsResponse(root);
                }
            }
            throw new ClaudeApiException("API_ERROR", "API 错误: 429", "重试耗尽");

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Claude API (tools) 调用失败", e);
            throw new ClaudeApiException("NETWORK_ERROR", "网络错误: " + e.getMessage(), "请检查网络连接和 API 地址");
        }
    }

    /**
     * 将 Claude 格式的单条 message 转换为 OpenAI 格式并追加到 msgsNode。
     * <ul>
     *   <li>普通 user/assistant 文本消息：直接映射</li>
     *   <li>assistant 含 tool_use block：转为 OpenAI tool_calls</li>
     *   <li>user 含 tool_result block：转为 OpenAI role=tool 消息</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void convertClaudeMessageToOpenAi(Map<String, Object> msg, ArrayNode msgsNode) throws Exception {
        String role = (String) msg.get("role");
        Object content = msg.get("content");

        if (content instanceof String) {
            // 纯文本消息，直接映射
            ObjectNode node = msgsNode.addObject();
            node.put("role", role);
            node.put("content", (String) content);
            return;
        }

        if (!(content instanceof List)) {
            return;
        }

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

        // 判断是否为 tool_result（user 发回给 assistant 的工具执行结果）
        boolean isToolResult = blocks.stream()
                .anyMatch(b -> "tool_result".equals(b.get("type")));

        if (isToolResult) {
            // 每个 tool_result 转为独立的 role=tool 消息
            for (Map<String, Object> block : blocks) {
                if ("tool_result".equals(block.get("type"))) {
                    ObjectNode node = msgsNode.addObject();
                    node.put("role", "tool");
                    node.put("tool_call_id", String.valueOf(block.get("tool_use_id")));
                    Object blockContent = block.get("content");
                    node.put("content", blockContent != null ? String.valueOf(blockContent) : "");
                }
            }
        } else {
            // assistant 消息（可能含 text 和 tool_use）
            StringBuilder text = new StringBuilder();
            List<Map<String, Object>> toolCalls = new ArrayList<>();

            for (Map<String, Object> block : blocks) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    Object t = block.get("text");
                    if (t != null) text.append(t);
                } else if ("tool_use".equals(type)) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", block.get("id"));
                    tc.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", block.get("name"));
                    Object input = block.get("input");
                    fn.put("arguments", input instanceof String
                            ? (String) input
                            : mapper.writeValueAsString(input));
                    tc.put("function", fn);
                    toolCalls.add(tc);
                }
            }

            ObjectNode node = msgsNode.addObject();
            node.put("role", role);
            if (text.length() > 0) {
                node.put("content", text.toString());
            } else {
                node.putNull("content");
            }
            if (!toolCalls.isEmpty()) {
                node.set("tool_calls", mapper.convertValue(toolCalls, ArrayNode.class));
            }
        }
    }

    /**
     * 解析 OpenAI /v1/chat/completions 响应，转换为 Claude 风格的 ToolCallResponse。
     * rawContent 保持 Claude 格式，使得 QAEngineImpl 的 messages history 逻辑无需修改。
     */
    private ToolCallResponse parseOpenAiToolsResponse(JsonNode root) throws Exception {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");

        // OpenAI finish_reason "tool_calls" → Claude stop_reason "tool_use"
        String stopReason = "tool_calls".equals(finishReason) ? "tool_use" : "end_turn";

        String text = message.path("content").isNull() ? "" : message.path("content").asText("");

        List<ToolUseBlock> toolUses = new ArrayList<>();
        // rawContent 以 Claude 格式构建，供 QAEngineImpl 写入 messages history
        List<Map<String, Object>> rawContentList = new ArrayList<>();

        if (text != null && !text.isBlank()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            rawContentList.add(textBlock);
        }

        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String argsStr = tc.path("function").path("arguments").asText("{}");
                JsonNode input = mapper.readTree(argsStr);
                toolUses.add(new ToolUseBlock(id, name, input));

                // 转换为 Claude tool_use 格式保存到历史
                Map<String, Object> toolUseBlock = new LinkedHashMap<>();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", id);
                toolUseBlock.put("name", name);
                toolUseBlock.put("input", mapper.convertValue(input, Map.class));
                rawContentList.add(toolUseBlock);
            }
        }

        JsonNode rawContent = mapper.convertValue(rawContentList, JsonNode.class);
        return new ToolCallResponse(stopReason, text, toolUses, rawContent);
    }

    /**
     * 解析 Claude 原生 /v1/messages 响应。
     */
    private ToolCallResponse parseClaudeToolsResponse(JsonNode root) {
        String stopReason = root.path("stop_reason").asText();
        JsonNode content = root.path("content");

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

        return new ToolCallResponse(stopReason, textBuilder.toString(), toolUses, content);
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
