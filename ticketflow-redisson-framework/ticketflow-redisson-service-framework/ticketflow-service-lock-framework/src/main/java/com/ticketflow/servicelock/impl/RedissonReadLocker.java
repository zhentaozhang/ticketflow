package com.ticketflow.servicelock.impl;

import com.ticketflow.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 读锁实现——包装 redissonClient.getReadWriteLock().readLock()。
 * <p>
 * 读锁共享，写锁互斥；适合读多写少的场景
 */
@AllArgsConstructor
public class RedissonReadLocker implements ServiceLocker {

    private final RedissonClient redissonClient;

    @Override
    public RLock getLock(String lockKey) {
        return redissonClient.getReadWriteLock(lockKey).readLock();
    }

    @Override
    public RLock lock(String lockKey) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        lock.lock();
        return lock;
    }

    @Override
    public RLock lock(String lockKey, long leaseTime) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        lock.lock(leaseTime, TimeUnit.SECONDS);
        return lock;
    }

    @Override
    public RLock lock(String lockKey, TimeUnit unit, long leaseTime) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        lock.lock(leaseTime, unit);
        return lock;
    }

    @Override
    public boolean tryLock(String lockKey, TimeUnit unit, long waitTime) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        try {
            return lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean tryLock(String lockKey, TimeUnit unit, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getReadWriteLock(lockKey).readLock();
        // 仅在当前线程确实持锁时才解锁：租约超时/重连后当前线程可能已不再持锁，
        // 直接 unlock 会抛 IllegalMonitorStateException，在 finally 中掩盖业务真实异常
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public void unlock(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

}
