package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.service.BuildLogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}/logs")
public class BuildLogController {

    private final BuildLogService buildLogService;

    public BuildLogController(BuildLogService buildLogService) {
        this.buildLogService = buildLogService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getLogs(
            @PathVariable Long repoId,
            @RequestParam(defaultValue = "0") int fromIndex) {
        List<String> lines = buildLogService.getLines(repoId, fromIndex);
        boolean finished = buildLogService.isFinished(repoId);
        int total = buildLogService.getLineCount(repoId);
        return ApiResponse.ok(Map.of(
                "lines", lines,
                "total", total,
                "finished", finished
        ));
    }
}
