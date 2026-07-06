package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/** MCP 服务器配置表：已接入的 MCP 服务及连接/测试状态。 */
@Entity
@Table(name = "mcp_servers")
@Comment("MCP 服务器配置表：已接入的 MCP 服务及连接/测试状态")
public class McpServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    @Comment("MCP 服务器名称")
    private String name;

    @Column(nullable = false, length = 500)
    @Comment("MCP 服务器地址")
    private String url;

    @Column(nullable = false, length = 20)
    @Comment("传输方式：SSE | STDIO")
    private String transport = "SSE";  // SSE | STDIO

    @Column
    @Comment("是否启用")
    private Boolean enabled = true;

    @Column(length = 500)
    @Comment("描述")
    private String description;

    @Column(name = "last_test_status", length = 20)
    @Comment("最近一次连通性测试状态：OK | FAIL | UNTESTED")
    private String lastTestStatus = "UNTESTED";  // OK | FAIL | UNTESTED

    @Column(name = "last_test_message", length = 500)
    @Comment("最近一次测试的返回信息")
    private String lastTestMessage;

    @Column(name = "created_at")
    @Comment("创建时间")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLastTestStatus() { return lastTestStatus; }
    public void setLastTestStatus(String lastTestStatus) { this.lastTestStatus = lastTestStatus; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
