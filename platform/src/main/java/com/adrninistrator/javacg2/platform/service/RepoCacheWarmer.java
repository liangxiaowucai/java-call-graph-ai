package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 启动后台预热：应用就绪后，把所有已分析（READY/ANALYZED）仓库的数据异步加载进
 * {@link RepoDataStore} 内存缓存。避免重启后首次点击「仓库拓扑/调用链」时，
 * 在请求线程里同步全量加载 DB 造成的卡顿（该加载路径原本静默无日志）。
 *
 * <p>预热在后台线程池执行，不阻塞应用启动，也不影响未预热完成前的请求
 * （未命中会照常按需加载，只是慢一次）。
 */
@Component
public class RepoCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(RepoCacheWarmer.class);

    private final RepositoryRepo repositoryRepo;
    private final RepoDataStore repoDataStore;
    private final ExecutorService analysisExecutor;

    public RepoCacheWarmer(RepositoryRepo repositoryRepo, RepoDataStore repoDataStore,
                           @Qualifier("analysisExecutor") ExecutorService analysisExecutor) {
        this.repositoryRepo = repositoryRepo;
        this.repoDataStore = repoDataStore;
        this.analysisExecutor = analysisExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        List<RepositoryEntity> repos = repositoryRepo.findAll().stream()
                .filter(r -> "READY".equals(r.getStatus()) || "ANALYZED".equals(r.getStatus()))
                .toList();
        if (repos.isEmpty()) return;
        log.info("[RepoCacheWarmer] 启动预热 {} 个已分析仓库的内存缓存...", repos.size());
        for (RepositoryEntity repo : repos) {
            analysisExecutor.submit(() -> {
                try {
                    repoDataStore.warmup(repo.getId());
                } catch (Exception e) {
                    log.warn("[RepoCacheWarmer] 预热仓库 {} 失败(不影响使用，将按需加载): {}",
                            repo.getId(), e.getMessage());
                }
            });
        }
    }
}
