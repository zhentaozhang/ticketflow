package com.ticketflow;


import com.ticketflow.base.BaseThreadPool;
import com.ticketflow.namefactory.BusinessNameThreadFactory;
import com.ticketflow.rejectedexecutionhandler.ThreadPoolRejectedExecutionHandler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 全局业务线程池。
 * 用于定时任务、异步补偿、数据初始化等并行操作，
 * 避免占用 Tomcat / Gateway 的工作线程。
 * 上下文（MDC traceId / BaseParameterHolder）由 {@link BaseThreadPool} 透传
 */

public class BusinessThreadPool {
    private static final ThreadPoolExecutor execute = new ThreadPoolExecutor(
            /* corePoolSize = CPU + 1（IO 密集型场景预留） */
            Runtime.getRuntime().availableProcessors() + 1,
            /* maxPoolSize = CPU × 5 */
            Runtime.getRuntime().availableProcessors() * 5,
            /* keepAliveTime = 60s */
            60,
            TimeUnit.SECONDS,
            /* workQueue = 600，有界防 OOM */
            new ArrayBlockingQueue<>(600),
            new BusinessNameThreadFactory(),
            new ThreadPoolRejectedExecutionHandler.BusinessAbortPolicy());


    public static void execute(Runnable r) {
        execute.execute(BaseThreadPool.wrapTask(r, BaseThreadPool.getContextForTask(), BaseThreadPool.getContextForHold()));
    }


    public static <T> Future<T> submit(Callable<T> c) {
        return execute.submit(BaseThreadPool.wrapTask(c, BaseThreadPool.getContextForTask(), BaseThreadPool.getContextForHold()));
    }
}
