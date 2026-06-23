package com.adrninistrator.javacg2.platform.dto;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;

import java.time.LocalDateTime;

/**
 * 仓库列表 DTO - 只包含列表页需要的字段
 * 不包含 profile 和 overview 等超长文本字段
 */
public class RepositoryListDTO {
    
    private Long id;
    private String name;
    private String gitUrl;
    private String repoType;
    private String branch;
    private String lastCommitHash;
    private LocalDateTime lastSyncTime;
    private String status;
    private LocalDateTime createdAt;
    private String urlPathIdentifier;

    public RepositoryListDTO() {
    }

    /**
     * 从实体转换为 DTO
     */
    public static RepositoryListDTO fromEntity(RepositoryEntity entity) {
        if (entity == null) {
            return null;
        }
        
        RepositoryListDTO dto = new RepositoryListDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setGitUrl(entity.getGitUrl());
        dto.setRepoType(entity.getRepoType());
        dto.setBranch(entity.getBranch());
        dto.setLastCommitHash(entity.getLastCommitHash());
        dto.setLastSyncTime(entity.getLastSyncTime());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
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

    public String getUrlPathIdentifier() {
        return urlPathIdentifier;
    }

    public void setUrlPathIdentifier(String urlPathIdentifier) {
        this.urlPathIdentifier = urlPathIdentifier;
    }
}
