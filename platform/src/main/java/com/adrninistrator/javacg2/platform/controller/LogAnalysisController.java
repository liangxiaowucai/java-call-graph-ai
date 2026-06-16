package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.service.LogAnalyzer;
import com.adrninistrator.javacg2.platform.service.MockService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}")
public class LogAnalysisController {

    private final LogAnalyzer logAnalyzer;
    private final MockService mockService;

    public LogAnalysisController(LogAnalyzer logAnalyzer, MockService mockService) {
        this.logAnalyzer = logAnalyzer;
        this.mockService = mockService;
    }

    @PostMapping("/analyze-log")
    public ApiResponse<LogAnalyzer.LogAnalysisResult> analyzeLog(
            @PathVariable Long repoId,
            @RequestBody Map<String, String> body) {
        String entryMethod = body.get("entryMethod");
        String logText = body.get("logText");
        if (entryMethod == null || logText == null) {
            return ApiResponse.error("VALIDATION_ERROR", "请提供 entryMethod 和 logText", "");
        }
        return ApiResponse.ok(logAnalyzer.analyze(repoId, entryMethod, logText));
    }

    @GetMapping("/mock")
    public ApiResponse<MockService.MockConfig> getMock(
            @PathVariable Long repoId,
            @RequestParam String method) {
        MockService.MockConfig mock = mockService.getMock(repoId, method);
        return ApiResponse.ok(mock);
    }

    @PutMapping("/mock")
    public ApiResponse<String> saveMock(
            @PathVariable Long repoId,
            @RequestBody Map<String, String> body) {
        String method = body.get("method");
        String mockRequest = body.get("mockRequest");
        String mockResponse = body.get("mockResponse");
        if (method == null) {
            return ApiResponse.error("VALIDATION_ERROR", "请提供 method", "");
        }
        mockService.saveMock(repoId, method, mockRequest, mockResponse);
        return ApiResponse.ok("Mock 已保存");
    }

    @DeleteMapping("/mock")
    public ApiResponse<String> deleteMock(
            @PathVariable Long repoId,
            @RequestParam String method) {
        mockService.deleteMock(repoId, method);
        return ApiResponse.ok("Mock 已删除");
    }
}
