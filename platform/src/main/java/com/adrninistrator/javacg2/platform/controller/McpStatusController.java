package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 状态查询接口，供前端配置页面展示。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpStatusController {

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of(
            "enabled", true,
            "sseEndpoint", "http://localhost:8080/mcp/sse",
            "protocolVersion", "2024-11-05",
            "tools", List.of(
                Map.of("name", "listRepositories",  "description", "列出所有已完成分析的 Java 仓库，返回仓库 ID、名称和分析状态"),
                Map.of("name", "getCallGraph",      "description", "获取指定方法的调用链树（向下展开），了解接口完整实现路径"),
                Map.of("name", "getImpactAnalysis", "description", "影响分析：查找哪些方法直接调用到指定方法（单仓库一层）"),
                Map.of("name", "getImpactWithSource", "description", "改底层方法首选：向上追溯到入口并附带每个调用点源码片段与返回类型，判断改动是否破坏上层契约"),
                Map.of("name", "getCrossRepoImpact", "description", "跨项目影响分析：跨所有仓库追溯调用方，找出改动波及的其他项目入口"),
                Map.of("name", "getCrossRepoCallTree", "description", "跨项目调用链：向下展开调用树，被调用方在其他仓库时自动跨库续接"),
                Map.of("name", "getMethodSource",   "description", "获取指定方法的完整源码"),
                Map.of("name", "getBoundaries",     "description", "获取方法的外部依赖边界点（DB/HTTP/MQ/缓存/gRPC）"),
                Map.of("name", "semanticSearch",    "description", "用自然语言搜索相关方法，支持向量语义搜索"),
                Map.of("name", "listApiEndpoints",  "description", "列出仓库所有 API 入口（Controller/MQ 消费者/定时任务/gRPC）")
            )
        ));
    }
}
