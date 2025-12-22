package com.ticketflow.lock;

/**
 * 分布式锁任务。封装在分布式锁保护下执行的任务逻辑。
 */
@FunctionalInterface
public interface LockTask<V> {
    /**
     * 执行锁的任务
     * @return 结果
     */
    V execute();
}