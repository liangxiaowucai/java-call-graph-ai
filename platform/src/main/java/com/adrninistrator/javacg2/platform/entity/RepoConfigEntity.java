package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/** 仓库配置表：每个仓库解析出的配置项（含来源与默认值），供上线文档等展示。 */
@Entity
@Table(name = "repo_config")
@Comment("仓库配置表：每个仓库解析出的配置项（含来源与默认值）")
public class RepoConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "config_key", nullable = false, length = 500)
    @Comment("配置项键名")
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    @Comment("配置项当前值")
    private String configValue;

    /** FILE=从配置文件读取, DEFAULT=使用默认值, USER=用户自定义 */
    @Column(length = 20)
    @Comment("值来源：FILE=配置文件, DEFAULT=默认值, USER=用户自定义")
    private String source = "FILE";

    @Column(name = "default_value", columnDefinition = "TEXT")
    @Comment("默认值")
    private String defaultValue;

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
