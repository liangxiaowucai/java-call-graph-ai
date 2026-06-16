package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class UploadController {

    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);

    private final RepositoryRepo repositoryRepo;

    @Value("${platform.repo-base-dir:./data/repos}")
    private String repoBaseDir;

    public UploadController(RepositoryRepo repositoryRepo) {
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 上传 jar 文件到已有仓库（跳过编译，直接用于分析）
     */
    @PostMapping("/{id}/upload-jar")
    public ApiResponse<List<String>> uploadJar(@PathVariable Long id,
                                                @RequestParam("files") MultipartFile[] files) {
        RepositoryEntity repo = repositoryRepo.findById(id).orElse(null);
        if (repo == null) {
            return ApiResponse.error("NOT_FOUND", "仓库不存在", "");
        }

        // 在仓库目录下创建 uploaded_jars 目录
        Path jarDir = Path.of(repo.getLocalPath(), "uploaded_jars");
        try {
            Files.createDirectories(jarDir);
        } catch (IOException e) {
            return ApiResponse.error("IO_ERROR", "创建目录失败: " + e.getMessage(), "");
        }

        List<String> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || (!name.endsWith(".jar") && !name.endsWith(".war"))) {
                continue; // 跳过非 jar/war 文件
            }
            try {
                Path target = jarDir.resolve(name);
                file.transferTo(target.toFile());
                saved.add(name);
                logger.info("上传 jar: {} -> {}", name, target);
            } catch (IOException e) {
                logger.error("保存文件失败: {}", name, e);
            }
        }

        if (saved.isEmpty()) {
            return ApiResponse.error("NO_FILE", "未上传有效的 jar/war 文件", "请选择 .jar 或 .war 文件");
        }

        return ApiResponse.ok(saved);
    }

    /**
     * 直接上传 jar 创建新仓库（不走 Git）
     */
    @PostMapping("/upload")
    public ApiResponse<RepositoryEntity> uploadNewRepo(@RequestParam("files") MultipartFile[] files,
                                                        @RequestParam(defaultValue = "uploaded") String name) {
        String localPath = new File(repoBaseDir, name + "_" + System.currentTimeMillis()).getAbsolutePath();
        Path jarDir = Path.of(localPath, "uploaded_jars");
        try {
            Files.createDirectories(jarDir);
        } catch (IOException e) {
            return ApiResponse.error("IO_ERROR", "创建目录失败", "");
        }

        List<String> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            String fname = file.getOriginalFilename();
            if (fname == null || (!fname.endsWith(".jar") && !fname.endsWith(".war"))) continue;
            try {
                file.transferTo(jarDir.resolve(fname).toFile());
                saved.add(fname);
            } catch (IOException e) {
                logger.error("保存文件失败: {}", fname, e);
            }
        }

        if (saved.isEmpty()) {
            return ApiResponse.error("NO_FILE", "未上传有效的 jar/war 文件", "请选择 .jar 或 .war 文件");
        }

        RepositoryEntity entity = new RepositoryEntity();
        entity.setName(name);
        entity.setGitUrl("local://" + String.join(",", saved));
        entity.setRepoType("LOCAL");
        entity.setBranch("-");
        entity.setLocalPath(localPath);
        entity.setStatus("READY");
        entity.setCreatedAt(LocalDateTime.now());
        entity = repositoryRepo.save(entity);

        logger.info("通过上传创建仓库: {}, 文件: {}", name, saved);
        return ApiResponse.ok(entity);
    }
}
