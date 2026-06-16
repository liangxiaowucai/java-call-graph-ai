package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScheduledSyncService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledSyncService.class);

    private final RepositoryRepo repositoryRepo;
    private final RepositoryManager repositoryManager;

    @Value("${platform.sync.enabled:false}")
    private boolean syncEnabled;

    public ScheduledSyncService(RepositoryRepo repositoryRepo, RepositoryManager repositoryManager) {
        this.repositoryRepo = repositoryRepo;
        this.repositoryManager = repositoryManager;
    }

    @Scheduled(cron = "${platform.sync.cron:0 0 * * * *}")
    public void syncAllRepositories() {
        if (!syncEnabled) {
            return;
        }

        List<RepositoryEntity> repos = repositoryRepo.findAll();
        logger.info("定时同步开始，共 {} 个仓库", repos.size());

        for (RepositoryEntity repo : repos) {
            if (!"READY".equals(repo.getStatus())) {
                logger.info("跳过非 READY 状态的仓库: {} ({})", repo.getName(), repo.getStatus());
                continue;
            }
            try {
                repositoryManager.pullRepository(repo.getId());
                List<String> changes = repositoryManager.detectChanges(repo.getId());
                if (!changes.isEmpty()) {
                    logger.info("仓库 {} 检测到 {} 个变更文件", repo.getName(), changes.size());
                    // TODO: 触发增量分析
                } else {
                    logger.info("仓库 {} 无变更", repo.getName());
                }
            } catch (Exception e) {
                logger.error("仓库 {} 同步失败，将在下一周期重试", repo.getName(), e);
            }
        }

        logger.info("定时同步完成");
    }
}
