package com.ticketflow.util;


import com.ticketflow.constant.LockInfoType;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import com.ticketflow.servicelock.info.LockTimeOutStrategy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁编程式工具——方便在非注解方法中直接使用锁。
 * <p>
 * 内部组合 ServiceLockFactory + LockInfoHandleFactory，
 * 提供 lock() / tryLock() / lockAndHandle() 等同步语义方法
 */
@Slf4j
@AllArgsConstructor
public class ServiceLockTool {

    /**
     * 缓存重建锁的等待上限（秒）。
     * <p>
     * 和 {@code @ServiceLock} 注解的 {@code waitTime} 默认值保持一致（都是 10 秒）：
     * 外层注解锁本来就是“等 10 秒抢不到就快速失败”，内层手写锁不应该反而无限等。
     */
    private static final long DEFAULT_LOCK_WAIT_SECONDS = 10L;

    private final LockInfoHandleFactory lockInfoHandleFactory;

    private final ServiceLockFactory serviceLockFactory;

    /**
     * 没有返回值的加锁执行
     *
     * @param taskRun 要执行的任务
     * @param name    锁的业务名
     * @param keys    锁的标识
     *
     *
     */
    public void execute(TaskRun taskRun, String name, String[] keys) {
        execute(taskRun, name, keys, 20);
    }

    /**
     * 没有返回值的加锁执行
     *
     * @param taskRun  要执行的任务
     * @param name     锁的业务名
     * @param keys     锁的标识
     * @param waitTime 等待时间
     *
     *
     */
    public void execute(TaskRun taskRun, String name, String[] keys, long waitTime) {
        execute(LockType.Reentrant, taskRun, name, keys, waitTime);
    }

    /**
     * 没有返回值的加锁执行
     *
     * @param lockType 锁类型
     * @param taskRun  要执行的任务
     * @param name     锁的业务名
     * @param keys     锁的标识
     *
     *
     */
    public void execute(LockType lockType, TaskRun taskRun, String name, String[] keys) {
        execute(lockType, taskRun, name, keys, 20);
    }

    /**
     * 没有返回值的加锁执行
     *
     * @param lockType 锁类型
     * @param taskRun  要执行的任务
     * @param name     锁的业务名
     * @param keys     锁的标识
     * @param waitTime 等待时间
     *
     *
     */
    public void execute(LockType lockType, TaskRun taskRun, String name, String[] keys, long waitTime) {
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        String lockName = lockInfoHandle.simpleGetLockName(name, keys);
        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, waitTime);
        if (result) {
            try {
                taskRun.run();
            } finally {
                lock.unlock(lockName);
            }
        } else {
            LockTimeOutStrategy.FAIL.handler(lockName);
        }
    }

    /**
     * 有返回值的加锁执行
     *
     * @param taskCall 要执行的任务
     * @param name     锁的业务名
     * @param keys     锁的标识
     * @return 要执行的任务的返回值
     *
     */
    public <T> T submit(TaskCall<T> taskCall, String name, String[] keys) {
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        String lockName = lockInfoHandle.simpleGetLockName(name, keys);
        ServiceLocker lock = serviceLockFactory.getLock(LockType.Reentrant);
        boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, 30);
        if (result) {
            try {
                return taskCall.call();
            } finally {
                lock.unlock(lockName);
            }
        } else {
            LockTimeOutStrategy.FAIL.handler(lockName);
            throw new RuntimeException(lockName + "请求频繁");
        }
    }

    /**
     * 获得锁
     *
     * @param lockType 锁类型
     * @param name     锁的业务名
     * @param keys     锁的标识
     *
     *
     */
    public RLock getLock(LockType lockType, String name, String[] keys) {
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        String lockName = lockInfoHandle.simpleGetLockName(name, keys);
        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        return lock.getLock(lockName);
    }

    /**
     * 获得锁
     *
     * @param lockType 锁类型
     * @param lockName 锁名
     *
     *
     */
    public RLock getLock(LockType lockType, String lockName) {
        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        return lock.getLock(lockName);
    }

    /**
     * 带等待上限的加锁，用于“等锁只是为了拿到别人算好的缓存”这类单飞场景。
     * <p>
     * 为什么这些内部锁也要有上限：它们保护的是“从 DB 重建缓存”这段重活（比如一个票档两万个座位），
     * 正常几十到几百毫秒就结束了；等满 10 秒说明持有锁的那个请求已经卡住。
     * 这时候继续等下去只会把自己的线程也堆在锁上，最后还是把服务拖垮。
     * <p>
     * 拿不到锁时调用方应当：<b>再查一次缓存</b>（有可能刚好被填好），
     * 仍然没有就快速失败——<b>不要改成无锁重建</b>，那等于把数据库也一起拖下水。
     *
     * @param lock     通过 {@link #getLock} 得到的锁
     * @param lockDesc 仅用于日志的锁描述
     * @return true = 已持有锁（调用方必须在 finally 里释放）；false = 没拿到，不要释放
     */
    public boolean tryLock(RLock lock, String lockDesc) {
        try {
            if (lock.tryLock(DEFAULT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            log.warn("等待锁超时，调用方将再查一次缓存 lock : {}", lockDesc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待锁被中断 lock : {}", lockDesc, e);
        } catch (Exception e) {
            log.error("获取锁异常 lock : {}", lockDesc, e);
        }
        return false;
    }
}
