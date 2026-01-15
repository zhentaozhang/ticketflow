package com.ticketflow.reconciliation;

import jakarta.annotation.Nonnull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 对账线程池。为对账任务提供独立线程池，避免影响业务主流程。
 */
public class ThreadPool {
    
    /**
     * 初始化异步执行队列线程池
     * */
    private final static ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() + 1,
            maximumPoolSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
                private int count = 1;
                @Override
                public Thread newThread(@Nonnull Runnable r) {
                    Thread t = defaultFactory.newThread(r);
                    t.setName("Init ReconciliationTask-" + count++);
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由提交线程执行，不丢任务
    );
    
    // availableProcessors / 0.2 = 5× core 数，即假设每个核心在峰值时只服务 20% 线程
    private static Integer maximumPoolSize() {
        return new BigDecimal(Runtime.getRuntime().availableProcessors())
                .divide(new BigDecimal("0.2"), 0, RoundingMode.HALF_UP).intValue();
    }
    
    public static ExecutorService getThreadPool( ){
        return EXECUTOR_SERVICE;
    }
}
