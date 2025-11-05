package com.ticketflow.servicelock;

import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁接口——4 种锁类型（Reentrant / Fair / Read / Write）统一契约。
 *
 * 各实现类包装 Redisson 原生锁对象，由 ServiceLockFactory 按 LockType 分发
 */
public interface ServiceLocker {
    
    /**
     * 获取锁
     * @param lockKey 锁的key
     * @return 结果
     * */
    RLock getLock(String lockKey);
    
    /**
     * 加锁
     * @param lockKey 锁的key
     * @return 结果
     * */
    RLock lock(String lockKey);
    
    /**
     * 加锁
     * @param lockKey 锁的key
     * @param leaseTime 释放时间
     * @return 结果
     * */
    RLock lock(String lockKey, long leaseTime);
    
    /**
     * 加锁
     * @param lockKey 锁的key
     * @param unit 时间单位
     * @param leaseTime 释放时间
     * @return 结果
     * */
    RLock lock(String lockKey, TimeUnit unit, long leaseTime);
    
    /**
     * 加锁
     * @param lockKey 锁的key
     * @param unit 时间单位
     * @param waitTime 等待时间
     * @return 结果
     * */
    boolean tryLock(String lockKey, TimeUnit unit, long waitTime);
    
    /**
     * 加锁
     * @param lockKey 锁的key
     * @param unit 时间单位
     * @param waitTime 等待时间
     * @param leaseTime 释放时间
     * @return 结果
     * */
    boolean tryLock(String lockKey, TimeUnit unit, long waitTime, long leaseTime);
    
    /**
     * 解锁
     * @param lockKey 锁的key
     * */
    void unlock(String lockKey);
    
    /**
     * 解锁
     * @param lock 锁
     * */
    void unlock(RLock lock);
}