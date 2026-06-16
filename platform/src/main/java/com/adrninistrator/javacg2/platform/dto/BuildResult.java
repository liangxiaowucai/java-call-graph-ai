package com.adrninistrator.javacg2.platform.dto;

/**
 * Gradle 编译结果
 */
public record BuildResult(
    boolean success,
    int exitCode,
    String output,
    String buildOutputPath
) {}
