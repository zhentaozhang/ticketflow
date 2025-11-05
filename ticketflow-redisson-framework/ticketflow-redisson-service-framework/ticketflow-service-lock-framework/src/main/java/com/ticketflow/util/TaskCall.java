package com.ticketflow.util;

/**
 * 带返回值的分布式锁任务回调——函数式接口。
 *
 * 用于 ServiceLockTool.lockAndHandle() 等编程式锁场景，
 * 调用方实现 V call() 作为锁内执行的业务逻辑
 */
@FunctionalInterface
public interface TaskCall<V> {

    /**
     * 执行任务
     * @return 结果
     * */
    V call();
}
