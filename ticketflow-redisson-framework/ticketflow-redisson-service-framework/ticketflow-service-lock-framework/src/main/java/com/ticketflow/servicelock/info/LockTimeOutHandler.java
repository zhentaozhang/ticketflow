package com.ticketflow.servicelock.info;

/**
 * 锁超时处理器接口——由 LockTimeOutStrategy 实现。
 *
 * handler(lockName) 在锁等待超时时被调用，决定是重试 / 快速失败 / 阻塞等待
 */
public interface LockTimeOutHandler {
    
    /**
     * 处理
     * @param lockName 锁名
     * */
    void handler(String lockName);
}
