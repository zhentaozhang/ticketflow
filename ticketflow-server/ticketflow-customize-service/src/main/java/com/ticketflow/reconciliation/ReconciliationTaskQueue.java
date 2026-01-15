package com.ticketflow.reconciliation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 对账任务队列。基于BlockingQueue的对账任务调度，支持延时执行和批量处理。
 */
@Slf4j
@Component
public class ReconciliationTaskQueue {
    
    /**
     * 重试次数
     * */
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    /**
     * 阻塞队列，保证 FIFO
     */
    private final BlockingQueue<ReconciliationTask> QUEUE = new LinkedBlockingQueue<>();
    
    

    /**
     * 消费线程池（可调节核心/最大线程数）
     * */
    private final ExecutorService executorService = new ThreadPoolExecutor(
            4,
            8,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
                private int count = 1;
                @Override
                public Thread newThread(@Nonnull Runnable r) {
                    Thread t = defaultFactory.newThread(r);
                    t.setName("ReconciliationTask-Worker-" + count++);
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 业务方调用：放任务进队列
     */
    public void putTask(ReconciliationTask task) {
        try {
            QUEUE.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("放入任务失败", e);
        }
    }

    /**
     * Spring 启动后，后台线程持续消费队列任务
     */
    @PostConstruct
    public void startConsumer() {
        ThreadPool.getThreadPool().execute(() -> {
            for (;;) {
                try {
                    // 阻塞获取任务
                    ReconciliationTask task = QUEUE.take();
                    // 提交到线程池执行，并带上重试逻辑
                    executorService.submit(() -> executeWithRetry(task));
                } catch (Exception ex) {
                    log.error("startConsumer error",ex);
                }
            }
        });
    }

    /**
     * 执行任务，失败时重试
     */
    private void executeWithRetry(ReconciliationTask task) {
        int attempt = 0;
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                task.run();
                return; // 成功就退出
            } catch (Exception e) {
                attempt++;
                log.error("任务执行失败，第 {} 次重试: {}", attempt, e.getMessage(), e);
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    log.error("任务执行失败，超过最大重试次数，丢弃任务");
                } else {
                    try {
                        // 可加点延时再重试
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
}