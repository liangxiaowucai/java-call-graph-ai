package com.adrninistrator.javacg2.platform.dto;

/**
 * 变更文件信息，用于增量更新检测
 */
public record ChangedFile(
    String filePath,
    ChangeType changeType
) {
    /**
     * 文件变更类型
     */
    public enum ChangeType {
        /** 新增文件 */
        ADD,
        /** 修改文件 */
        MODIFY,
        /** 删除文件 */
        DELETE,
        /** 重命名文件 */
        RENAME,
        /** 复制文件 */
        COPY
    }
}
