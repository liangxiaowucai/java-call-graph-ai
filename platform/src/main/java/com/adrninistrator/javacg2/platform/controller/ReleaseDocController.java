package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.ReleaseDocService;
import com.adrninistrator.javacg2.platform.service.ReleaseDocService.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/release-doc")
@CrossOrigin(originPatterns = "*")
public class ReleaseDocController {

    private final ReleaseDocService releaseDocService;
    private final RepositoryRepo repositoryRepo;

    public ReleaseDocController(ReleaseDocService releaseDocService, RepositoryRepo repositoryRepo) {
        this.releaseDocService = releaseDocService;
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 获取仓库的所有分支列表
     */
    @GetMapping("/branches/{repoId}")
    public ApiResponse<List<String>> getBranches(@PathVariable Long repoId) {
        var repo = repositoryRepo.findById(repoId).orElse(null);
        if (repo == null) return ApiResponse.error("NOT_FOUND", "仓库不存在", "");

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "-a", "--format=%(refname:short)");
            pb.directory(new java.io.File(repo.getLocalPath()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            List<String> branches = output.lines()
                .map(String::trim)
                .filter(b -> !b.isBlank())
                .map(b -> b.startsWith("origin/") ? b.substring(7) : b)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

            return ApiResponse.ok(branches);
        } catch (Exception e) {
            return ApiResponse.error("GIT_ERROR", "获取分支失败: " + e.getMessage(), "");
        }
    }

    /**
     * 生成上线文档
     * 请求体: { selections: [{repoId, branch, baseBranch}] }
     */
    @PostMapping("/generate")
    public ApiResponse<ReleaseDoc> generate(@RequestBody Map<String, List<Map<String, Object>>> body) {
        List<Map<String, Object>> selections = body.get("selections");
        if (selections == null || selections.isEmpty()) {
            return ApiResponse.error("BAD_REQUEST", "请选择至少一个仓库和分支", "");
        }

        List<RepoSelection> repoSelections = selections.stream().map(m -> {
            Long repoId = ((Number) m.get("repoId")).longValue();
            String branch = (String) m.get("branch");
            String baseBranch = m.containsKey("baseBranch") ? (String) m.get("baseBranch") : "master";
            return new RepoSelection(repoId, branch, baseBranch);
        }).collect(Collectors.toList());

        try {
            ReleaseDoc doc = releaseDocService.generate(repoSelections);
            return ApiResponse.ok(doc);
        } catch (Exception e) {
            return ApiResponse.error("GENERATE_ERROR", "生成失败: " + e.getMessage(), "");
        }
    }

    /**
     * 按需获取单个变更文件的 git diff（供前端「详情」左右源码对比）
     * 请求体: { repoId, filePath, baseBranch, branch }
     */
    @PostMapping("/file-diff")
    public ApiResponse<ReleaseDocService.FileDiffResult> fileDiff(@RequestBody Map<String, Object> body) {
        if (body.get("repoId") == null || body.get("filePath") == null || body.get("branch") == null) {
            return ApiResponse.error("BAD_REQUEST", "缺少 repoId/filePath/branch", "");
        }
        Long repoId = ((Number) body.get("repoId")).longValue();
        String filePath = (String) body.get("filePath");
        String branch = (String) body.get("branch");
        String baseBranch = body.containsKey("baseBranch") ? (String) body.get("baseBranch") : "master";
        try {
            return ApiResponse.ok(releaseDocService.fileDiff(repoId, filePath, baseBranch, branch));
        } catch (Exception e) {
            return ApiResponse.error("DIFF_ERROR", "获取 diff 失败: " + e.getMessage(), "");
        }
    }
}
