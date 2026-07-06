package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/** 系统配置表：平台全局配置（如 AI/Embedding 的 url、key 等键值对）。 */
@Entity
@Table(name = "system_config")
@Comment("系统配置表：平台全局配置键值对（如 AI/Embedding 的 url、key）")
public class SystemConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    @Comment("配置键名（唯一）")
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    @Comment("配置值")
    private String configValue;

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
