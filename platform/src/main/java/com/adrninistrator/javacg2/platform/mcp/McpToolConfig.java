package com.adrninistrator.javacg2.platform.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 JavaCallGraphMcpService 的 @Tool 方法注册到 Spring AI MCP Server。
 * 注册后，AI 编程助手通过 MCP 协议即可调用这些工具。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider callGraphTools(JavaCallGraphMcpService service) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(service)
                .build();
    }
}
