package com.adrninistrator.javacg2.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 分析任务线程池：单线程顺序执行，队列最多排 10 个
     */
    @Bean(name = "analysisExecutor")
    public ExecutorService analysisExecutor() {
        return new ThreadPoolExecutor(
                1, 1,
                0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r, "analysis-worker");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    throw new RejectedExecutionException("分析队列已满（最多排队 10 个），请稍后再试");
                }
        );
    }

    /**
     * Embedding 任务线程池：单线程后台执行，不阻塞分析主流程
     * 队列最多排 20 个仓库的任务
     */
    @Bean(name = "embeddingExecutor")
    public java.util.concurrent.Executor embeddingExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1,
                0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                r -> {
                    Thread t = new Thread(r, "embedding-worker");
                    t.setDaemon(true);
                    return t;
                },
                (r, exec) -> {
                    throw new RejectedExecutionException("Embedding 队列已满（最多排队 20 个），请稍后再试");
                }
        );
        return executor;
    }
}
