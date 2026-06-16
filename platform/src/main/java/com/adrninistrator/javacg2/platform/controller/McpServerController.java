package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.entity.McpServerEntity;
import com.adrninistrator.javacg2.platform.repository.McpServerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 管理：平台作为 MCP Client 调用外部服务。
 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private static final Logger logger = LoggerFactory.getLogger(McpServerController.class);

    private final McpServerRepo mcpServerRepo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public McpServerController(McpServerRepo mcpServerRepo) {
        this.mcpServerRepo = mcpServerRepo;
    }

    /** 列出所有外部 MCP Server */
    @GetMapping
    public ApiResponse<List<McpServerEntity>> list() {
        return ApiResponse.ok(mcpServerRepo.findAllByOrderByCreatedAtAsc());
    }

    /** 新增外部 MCP Server */
    @PostMapping
    public ApiResponse<McpServerEntity> create(@RequestBody McpServerEntity entity) {
        entity.setId(null);
        entity.setLastTestStatus("UNTESTED");
        entity.setLastTestMessage(null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return ApiResponse.ok(mcpServerRepo.save(entity));
    }

    /** 更新外部 MCP Server */
    @PutMapping("/{id}")
    public ApiResponse<McpServerEntity> update(@PathVariable Long id, @RequestBody McpServerEntity body) {
        McpServerEntity entity = mcpServerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP Server 不存在: " + id));
        entity.setName(body.getName());
        entity.setUrl(body.getUrl());
        entity.setTransport(body.getTransport());
        entity.setEnabled(body.getEnabled());
        entity.setDescription(body.getDescription());
        entity.setUpdatedAt(LocalDateTime.now());
        return ApiResponse.ok(mcpServerRepo.save(entity));
    }

    /** 删除外部 MCP Server */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        mcpServerRepo.deleteById(id);
        return ApiResponse.ok("已删除");
    }

    /**
     * 连接测试：向目标 URL 发送 HTTP GET（SSE 端点握手），验证可达性。
     * 不走完整 MCP 握手，只验证 HTTP 连通性。
     */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        McpServerEntity entity = mcpServerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP Server 不存在: " + id));

        String url = entity.getUrl();
        String status;
        String message;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream,application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();

            if (code == 200 || code == 201 || code == 204) {
                status = "OK";
                message = "连接正常 (HTTP " + code + ")";
            } else {
                status = "FAIL";
                message = "HTTP " + code + ": " + response.body().substring(0, Math.min(100, response.body().length()));
            }
        } catch (Exception e) {
            status = "FAIL";
            message = "连接失败: " + e.getMessage();
            logger.warn("[MCP Client] 连接测试失败 url={}: {}", url, e.getMessage());
        }

        entity.setLastTestStatus(status);
        entity.setLastTestMessage(message);
        entity.setUpdatedAt(LocalDateTime.now());
        mcpServerRepo.save(entity);

        return ApiResponse.ok(Map.of("status", status, "message", message, "url", url));
    }
}
