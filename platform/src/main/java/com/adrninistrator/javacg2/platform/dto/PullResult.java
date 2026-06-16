package com.adrninistrator.javacg2.platform.dto;

/**
 * Git pull 操作结果
 */
public record PullResult(
    boolean updated,
    String previousCommitHash,
    String currentCommitHash,
    String message
) {}
