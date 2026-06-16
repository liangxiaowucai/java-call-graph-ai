package com.adrninistrator.javacg2.platform.controller;

import com.adrninistrator.javacg2.platform.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/files")
public class FileExplorerController {

    @GetMapping("/browse")
    public ApiResponse<List<FileItem>> browse(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "all") String filter) {

        File dir;
        if (path.isEmpty()) {
            dir = new File(System.getProperty("user.home"));
        } else {
            dir = new File(path);
        }

        if (!dir.exists()) {
            return ApiResponse.error("NOT_FOUND", "路径不存在: " + path, "请检查路径");
        }

        // 如果是文件，返回其父目录内容
        if (dir.isFile()) {
            dir = dir.getParentFile();
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return ApiResponse.error("ACCESS_DENIED", "无法读取目录: " + path, "请检查权限");
        }

        List<FileItem> items = new ArrayList<>();

        // 添加上级目录
        File parent = dir.getParentFile();
        if (parent != null) {
            items.add(new FileItem("..", parent.getAbsolutePath(), true, 0));
        }

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            if (f.isHidden()) continue;

            if ("dir".equals(filter) && !f.isDirectory()) continue;
            if ("xml".equals(filter) && !f.isDirectory() && !f.getName().endsWith(".xml")) continue;

            items.add(new FileItem(f.getName(), f.getAbsolutePath(), f.isDirectory(), f.length()));
        }

        return ApiResponse.ok(items);
    }

    public record FileItem(String name, String path, boolean isDir, long size) {}
}
