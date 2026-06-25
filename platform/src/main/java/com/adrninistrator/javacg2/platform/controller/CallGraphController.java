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

    /** 按短引用（类名.方法名）解析完整方法签名候选，供问答中点击方法名跳转源码 */
    @GetMapping("/resolve-method")
    public ApiResponse<List<String>> resolveMethod(@PathVariable Long repoId, @RequestParam String shortRef) {
        return ApiResponse.ok(callGraphEngine.resolveMethodsByShortRef(repoId, shortRef));
    }

    @GetMapping("/call-tree")
    public ApiResponse<CallGraphEngine.CallTreeDTO> getCallTree(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(defaultValue = "3") int maxDepth) {  // 改为3，初始只加载浅层
        
        // 限制最大深度为5，防止性能问题（深度20会导致119秒）
        if (maxDepth > 5) {
            maxDepth = 5;
        }
        
        if (maxDepth < 1) {
            maxDepth = 1;
        }
        
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
    
    @GetMapping("/doc/diagrams")
    public ApiResponse<Map<String, String>> getProductDocDiagrams(@PathVariable Long repoId, @RequestParam String method) {
        return ApiResponse.ok(docGenerator.generateProductDocDiagrams(repoId, method));
    }
    
    /**
     * 懒加载：展开指定节点的子树
     * 用于前端点击节点时动态加载其下游调用
     */
    @GetMapping("/expand-node")
    public ApiResponse<CallGraphEngine.CallTreeDTO> expandNode(
            @PathVariable Long repoId,
            @RequestParam String method,
            @RequestParam(defaultValue = "2") int depth) {
        
        // 限制每次展开的深度
        if (depth > 3) {
            depth = 3;
        }
        
        if (depth < 1) {
            depth = 1;
        }
        
        return ApiResponse.ok(callGraphEngine.expandCallTree(repoId, method, depth));
    }
}
