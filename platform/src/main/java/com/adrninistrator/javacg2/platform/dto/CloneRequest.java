package com.adrninistrator.javacg2.platform.dto;

public record CloneRequest(
    String gitUrl,
    String token,
    String repoType,
    String branch,
    String packagePrefix,
    String urlPathIdentifier
) {}
