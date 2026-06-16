package com.adrninistrator.javacg2.platform.dto;

import java.time.LocalDateTime;

/**
 * 仓库信息 DTO，用于 API 响应
 */
public record RepoInfo(
    Long id,
    String name,
    String gitUrl,
    String localPath,
    String branch,
    String lastCommitHash,
    LocalDateTime lastSyncTime,
    String status
) {}
