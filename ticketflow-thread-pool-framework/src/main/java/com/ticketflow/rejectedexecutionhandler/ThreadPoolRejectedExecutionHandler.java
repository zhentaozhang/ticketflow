package com.ticketflow.rejectedexecutionhandler;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池拒绝策略。
 * 记录详细的拒绝日志与指标监控
 */
@Slf4j
public class ThreadPoolRejectedExecutionHandler {

    public static class BusinessAbortPolicy implements RejectedExecutionHandler {

        public BusinessAbortPolicy() {
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("[BusinessThreadPool Rejected] 业务任务被线程池拒绝！队列积压: {}, 活跃线程数: {}, 线程池大小: {}",
                    executor.getQueue().size(),
                    executor.getActiveCount(),
                    executor.getPoolSize());

            throw new RejectedExecutionException("BusinessThreadPool rejected task " + r.toString() +
                    " from " + executor.toString());
        }
    }
}
