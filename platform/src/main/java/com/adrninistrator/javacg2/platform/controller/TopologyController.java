package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TopologyController {

    private final CallGraphEngine callGraphEngine;

    public TopologyController(CallGraphEngine callGraphEngine) {
        this.callGraphEngine = callGraphEngine;
    }

    /** 仓库拓扑图：所有仓库节点 + 跨库调用边 */
    @GetMapping("/topology")
    public ApiResponse<CallGraphEngine.TopologyDTO> getTopology() {
        return ApiResponse.ok(callGraphEngine.getTopology());
    }

    /** 跨库影响分析：向上追溯所有仓库中谁调用了该方法 */
    @GetMapping("/cross-repo/impact")
    public ApiResponse<CallGraphEngine.CrossRepoImpactDTO> getCrossRepoImpact(
            @RequestParam String method,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return ApiResponse.ok(callGraphEngine.getCrossRepoImpact(method, maxDepth));
    }

    /** 跨库调用链：向下追踪，被调用方在其他仓库时自动续接 */
    @GetMapping("/cross-repo/tree")
    public ApiResponse<CallGraphEngine.CrossRepoTreeDTO> getCrossRepoCallTree(
            @RequestParam String method,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return ApiResponse.ok(callGraphEngine.getCrossRepoCallTree(method, maxDepth));
    }
}
