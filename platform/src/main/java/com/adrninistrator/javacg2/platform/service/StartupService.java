package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StartupService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupService.class);

    private final RepositoryRepo repositoryRepo;
    private final JdbcTemplate jdbcTemplate;

    public StartupService(RepositoryRepo repositoryRepo, JdbcTemplate jdbcTemplate) {
        this.repositoryRepo = repositoryRepo;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // schema 迁移：加新字段
        addColumnIfNotExists("repositories", "profile", "TEXT");
        addColumnIfNotExists("repositories", "overview", "TEXT");

        // 重启时把卡在中间状态的仓库重置为 READY 或 ERROR
        List<RepositoryEntity> repos = repositoryRepo.findAll();
        for (RepositoryEntity repo : repos) {
            String status = repo.getStatus();
            if ("ANALYZING".equals(status) || "BUILDING".equals(status) || "CLONING".equals(status) || "QUEUED".equals(status) || "GENERATING_DOC".equals(status)) {
                logger.info("重置卡住的仓库状态: {} ({} -> ERROR)", repo.getName(), status);
                repo.setStatus("ERROR");
                repositoryRepo.save(repo);
            }
        }
    }

    private void addColumnIfNotExists(String table, String column, String type) {
        try {
            jdbcTemplate.execute("SELECT " + column + " FROM " + table + " LIMIT 1");
        } catch (Exception e) {
            logger.info("迁移: 添加字段 {}.{}", table, column);
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}
