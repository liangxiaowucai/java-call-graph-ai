package com.adrninistrator.javacg2.platform.service;

import java.util.List;

public interface RepositoryManager {

    /** 获取远程仓库分支列表 */
    List<String> listRemoteBranches(String gitUrl, String token, String repoType);

    /** 克隆仓库到本地 */
    RepoResult cloneRepository(String gitUrl, String token, String repoType, String branch);

    /** 拉取最新代码 */
    RepoResult pullRepository(Long repoId);

    /** 检测变更文件列表 */
    List<String> detectChanges(Long repoId);

    /** 触发编译 */
    RepoResult triggerBuild(Long repoId);

    /** 删除仓库（本地文件+数据库记录） */
    void deleteRepository(Long repoId);

    record RepoResult(boolean success, String message, Long repoId) {}
}
