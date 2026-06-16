package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.exception.AnalysisException;
import com.adrninistrator.javacg2.platform.exception.ClaudeApiException;
import com.adrninistrator.javacg2.platform.exception.GitOperationException;
import com.adrninistrator.javacg2.platform.exception.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GitOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleGitError(GitOperationException e) {
        logger.error("Git operation error: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getErrorType(), e.getMessage(), e.getSuggestion()));
    }

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<ApiResponse<Void>> handleAnalysisError(AnalysisException e) {
        logger.error("Analysis error: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getErrorType(), e.getMessage(), e.getSuggestion()));
    }

    @ExceptionHandler(ClaudeApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleClaudeError(ClaudeApiException e) {
        logger.error("Claude API error: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getErrorType(), e.getMessage(), e.getSuggestion()));
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatformError(PlatformException e) {
        logger.error("Platform error: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getErrorType(), e.getMessage(), e.getSuggestion()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        // 忽略 favicon.ico 等静态资源 404
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericError(Exception e) {
        logger.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.error("INTERNAL_ERROR", e.getMessage(), "请联系管理员"));
    }
}
