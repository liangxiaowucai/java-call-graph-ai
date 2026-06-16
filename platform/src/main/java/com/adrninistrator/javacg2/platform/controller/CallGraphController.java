package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.service.CallGraphEngine;
import com.adrninistrator.javacg2.platform.service.impl.CallChainCodeGenerator;
import com.adrninistrator.javacg2.platform.service.impl.DocGenerator;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos/{repoId}")
public class CallGraphController {

    private final CallGraphEngine callGraphEngine;
    private final CallChainCodeGenerator codeGenerator;
    private final DocGenerator docGenerator;

    public CallGraphController(CallGraphEngine callGraphEngine, CallChainCodeGenerator codeGenerator, DocGenerator docGenerator) {
        this.callGraphEngine = callGraphEngine;
        this.codeGenerator = codeGenerator;
        this.docGenerator = docGenerator;
    }

    @GetMapping("/entry-points")
    public ApiResponse<List<CallGraphEngine.EntryPointDTO>> getEntryPoints(@PathVariable Long repoId) {
        return ApiResponse.ok(callGraphEngine.getEntryPoints(repoId));
    }

    @GetMapping("/call-tree")
    public ApiResponse<CallGraphEngine.CallTreeDTO> getCallTree(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(defaultValue = "20") int maxDepth) {
        return ApiResponse.ok(callGraphEngine.expandCallTree(repoId, method, maxDepth));
    }

    @GetMapping("/callers")
    public ApiResponse<List<CallGraphEngine.CallerDTO>> getCallers(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(defaultValue = "5") int depth) {
        return ApiResponse.ok(callGraphEngine.getCallers(repoId, method, depth));
    }

    @GetMapping("/callees")
    public ApiResponse<List<CallGraphEngine.CallerDTO>> getCallees(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(defaultValue = "5") int depth) {
        return ApiResponse.ok(callGraphEngine.getCallees(repoId, method, depth));
    }

    @GetMapping("/source")
    public ApiResponse<String> getMethodSource(
            @PathVariable Long repoId,
            @RequestParam String method) {
        String source = callGraphEngine.getMethodSource(repoId, method);
        if (source == null) {
            return ApiResponse.error("NOT_FOUND", "未找到方法源码", "请确认方法签名正确");
        }
        return ApiResponse.ok(source);
    }

    @GetMapping("/source-detail")
    public ApiResponse<CallGraphEngine.MethodSourceDTO> getMethodSourceDetail(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(required = false) String entryMethod) {
        var detail = callGraphEngine.getMethodSourceDetail(repoId, method, entryMethod);
        if (detail == null) {
            return ApiResponse.error("NOT_FOUND", "未找到方法源码", "请确认方法签名正确");
        }
        return ApiResponse.ok(detail);
    }

    @GetMapping("/generate-code")
    public ApiResponse<String> generateCode(
            @PathVariable Long repoId,
            @RequestParam String method) {
        String code = codeGenerator.generate(repoId, method);
        return ApiResponse.ok(code);
    }

    @PostMapping("/generate-code")
    public ApiResponse<String> generateCodeWithDiagnosis(
            @PathVariable Long repoId,
            @RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        @SuppressWarnings("unchecked")
        Map<String, String> statuses = (Map<String, String>) body.get("statuses");
        String code = codeGenerator.generate(repoId, method, statuses);
        return ApiResponse.ok(code);
    }

    @GetMapping("/doc/product")
    public ApiResponse<String> getProductDoc(@PathVariable Long repoId, @RequestParam String method) {
        return ApiResponse.ok(docGenerator.generateProductDoc(repoId, method));
    }

    @GetMapping("/doc/dev")
    public ApiResponse<String> getDevDoc(@PathVariable Long repoId, @RequestParam String method) {
        return ApiResponse.ok(docGenerator.generateDevDoc(repoId, method));
    }
}
