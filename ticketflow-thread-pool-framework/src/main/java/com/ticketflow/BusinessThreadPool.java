package com.ticketflow;


import com.ticketflow.base.BaseThreadPool;
import com.ticketflow.namefactory.BusinessNameThreadFactory;
import com.ticketflow.rejectedexecutionhandler.ThreadPoolRejectedExecutionHandler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 全局业务线程池。
 * 用于定时任务、异步补偿等非核心链路的并行操作，
 * 避免占用 Tomcat / Gateway 的工作线程
 */

public class BusinessThreadPool extends BaseThreadPool {
    private static ThreadPoolExecutor execute = null;

    static {
        execute = new ThreadPoolExecutor(
                /* corePoolSize = CPU + 1（IO 密集型场景预留） */
                Runtime.getRuntime().availableProcessors() + 1,
                /* maxPoolSize = CPU / 0.2 ≈ ×5 */
                maximumPoolSize(),
                /* keepAliveTime = 60s */
                60,
                TimeUnit.SECONDS,
                /* workQueue = 600，有界防 OOM */
                new ArrayBlockingQueue<>(600),
                new BusinessNameThreadFactory(),
                new ThreadPoolRejectedExecutionHandler.BusinessAbortPolicy());
    }

    private static Integer maximumPoolSize() {
        return new BigDecimal(Runtime.getRuntime().availableProcessors())
                .divide(new BigDecimal("0.2"), 0, RoundingMode.HALF_UP).intValue();
    }


    public static void execute(Runnable r) {
        execute.execute(wrapTask(r, getContextForTask(), getContextForHold()));
    }


    public static <T> Future<T> submit(Callable<T> c) {
        return execute.submit(wrapTask(c, getContextForTask(), getContextForHold()));
    }
}
