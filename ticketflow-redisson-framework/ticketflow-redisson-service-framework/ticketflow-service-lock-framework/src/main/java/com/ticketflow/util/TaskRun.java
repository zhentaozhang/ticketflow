package com.ticketflow.util;

/**
 * 无返回值的分布式锁任务回调——函数式接口。
 *
 * 用于 ServiceLockTool.lock() 等编程式锁场景，
 * 调用方实现 void run() 作为锁内执行的业务逻辑
 */
@FunctionalInterface
public interface TaskRun {
    
    /**
     * 执行任务
     * */
    void run();
}
