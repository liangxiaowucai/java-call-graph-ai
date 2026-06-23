package com.adrninistrator.javacg2.platform.dto;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;

import java.time.LocalDateTime;

/**
 * 仓库详情 DTO - 包含所有字段，用于详情接口
 * 包含 profile 和 overview 等完整信息
 */
public class RepositoryDetailDTO {
    
    private Long id;
    private String name;
    private String gitUrl;
    private String tokenEncrypted;
    private String repoType;
    private String branch;
    private String localPath;
    private String lastCommitHash;
    private LocalDateTime lastSyncTime;
    private String status;
    private LocalDateTime createdAt;
    private String profile;  // 完整的技术栈信息
    private String overview; // 完整的项目概览
    private String urlPathIdentifier;

    public RepositoryDetailDTO() {
    }

    /**
     * 从实体转换为详情 DTO
     */
    public static RepositoryDetailDTO fromEntity(RepositoryEntity entity) {
        if (entity == null) {
            return null;
        }
        
        RepositoryDetailDTO dto = new RepositoryDetailDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setGitUrl(entity.getGitUrl());
        dto.setTokenEncrypted(entity.getTokenEncrypted());
        dto.setRepoType(entity.getRepoType());
        dto.setBranch(entity.getBranch());
        dto.setLocalPath(entity.getLocalPath());
        dto.setLastCommitHash(entity.getLastCommitHash());
        dto.setLastSyncTime(entity.getLastSyncTime());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setProfile(entity.getProfile());
        dto.setOverview(entity.getOverview());
        dto.setUrlPathIdentifier(entity.getUrlPathIdentifier());
        
        return dto;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getTokenEncrypted() {
        return tokenEncrypted;
    }

    public void setTokenEncrypted(String tokenEncrypted) {
        this.tokenEncrypted = tokenEncrypted;
    }

    public String getRepoType() {
        return repoType;
    }

    public void setRepoType(String repoType) {
        this.repoType = repoType;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getLastCommitHash() {
        return lastCommitHash;
    }

    public void setLastCommitHash(String lastCommitHash) {
        this.lastCommitHash = lastCommitHash;
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(LocalDateTime lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getUrlPathIdentifier() {
        return urlPathIdentifier;
    }

    public void setUrlPathIdentifier(String urlPathIdentifier) {
        this.urlPathIdentifier = urlPathIdentifier;
    }
}
