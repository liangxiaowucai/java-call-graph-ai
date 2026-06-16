package com.adrninistrator.javacg2.platform.dto;

public record ErrorResponse(
    String errorType,
    String message,
    String suggestion
) {}
