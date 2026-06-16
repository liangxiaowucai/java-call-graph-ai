package com.adrninistrator.javacg2.platform.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class BuildLogService {

    // repoId -> log lines
    private final Map<Long, List<String>> logMap = new ConcurrentHashMap<>();
    // repoId -> finished flag
    private final Map<Long, Boolean> finishedMap = new ConcurrentHashMap<>();

    public void clear(Long repoId) {
        logMap.put(repoId, new CopyOnWriteArrayList<>());
        finishedMap.put(repoId, false);
    }

    public void append(Long repoId, String line) {
        logMap.computeIfAbsent(repoId, k -> new CopyOnWriteArrayList<>()).add(line);
    }

    public void finish(Long repoId, boolean success) {
        append(repoId, success ? "\n✅ 完成" : "\n❌ 失败");
        finishedMap.put(repoId, true);
        // 截断日志，只保留最后 200 行
        List<String> lines = logMap.get(repoId);
        if (lines != null && lines.size() > 200) {
            List<String> trimmed = new CopyOnWriteArrayList<>(lines.subList(lines.size() - 200, lines.size()));
            logMap.put(repoId, trimmed);
        }
    }

    public List<String> getLines(Long repoId, int fromIndex) {
        List<String> lines = logMap.get(repoId);
        if (lines == null || fromIndex >= lines.size()) return List.of();
        return lines.subList(fromIndex, lines.size());
    }

    public int getLineCount(Long repoId) {
        List<String> lines = logMap.get(repoId);
        return lines == null ? 0 : lines.size();
    }

    public boolean isFinished(Long repoId) {
        return finishedMap.getOrDefault(repoId, true);
    }
}
