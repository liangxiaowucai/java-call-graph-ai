package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/** 仓库表：接入的代码仓库及其分析状态、画像与概览。 */
@Entity
@Table(name = "repositories")
@Comment("仓库表：接入的代码仓库及其分析状态、画像与概览")
public class RepositoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Comment("仓库名称")
    private String name;

    @Column(name = "git_url", nullable = false, length = 500)
    @Comment("Git 仓库地址")
    private String gitUrl;

    @Column(name = "token_encrypted", length = 500)
    @Comment("访问令牌（加密存储）")
    private String tokenEncrypted;

    @Column(name = "repo_type", nullable = false, length = 20)
    @Comment("仓库类型（GITHUB/GITLAB 等）")
    private String repoType;

    @Column(length = 100)
    @Comment("分析使用的分支")
    private String branch = "main";

    @Column(name = "local_path", nullable = false, length = 500)
    @Comment("本地克隆路径")
    private String localPath;

    @Column(name = "last_commit_hash", length = 64)
    @Comment("最近一次分析的提交 hash")
    private String lastCommitHash;

    @Column(name = "last_sync_time")
    @Comment("最近一次同步/分析时间")
    private LocalDateTime lastSyncTime;

    @Column(length = 20)
    @Comment("状态：CREATED/CLONING/QUEUED/ANALYZING/READY/ERROR")
    private String status = "CREATED";

    @Column(name = "created_at")
    @Comment("创建时间")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(columnDefinition = "TEXT")
    @Comment("仓库画像（多仓库场景快速定位用）")
    private String profile;

    @Column(columnDefinition = "TEXT")
    @Comment("项目概览文档")
    private String overview;

    @Column(name = "url_path_identifier", length = 200)
    @Comment("URL 路径标识（用于接口 URL 归属识别）")
    private String urlPathIdentifier;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGitUrl() { return gitUrl; }
    public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }
    public String getTokenEncrypted() { return tokenEncrypted; }
    public void setTokenEncrypted(String tokenEncrypted) { this.tokenEncrypted = tokenEncrypted; }
    public String getRepoType() { return repoType; }
    public void setRepoType(String repoType) { this.repoType = repoType; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getLastCommitHash() { return lastCommitHash; }
    public void setLastCommitHash(String lastCommitHash) { this.lastCommitHash = lastCommitHash; }
    public LocalDateTime getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(LocalDateTime lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }
    public String getUrlPathIdentifier() { return urlPathIdentifier; }
    public void setUrlPathIdentifier(String urlPathIdentifier) { this.urlPathIdentifier = urlPathIdentifier; }
}
