/**
 * @RepeatExecuteLimit 注解的 AOP 实现——基于分布式锁的防重复提交。
 * <p>
 * 流程：@RepeatExecuteLimit → 本地锁（防止同 JVM 并发）→ 分布式锁（跨进程互斥）→
 * 执行成功后在 Redis 中标记去重（setIfAbsent + ttl）
 * <p>
 * 在锁超时/失败时抛出 TicketFlowFrameException
 */
package com.ticketflow.repeatexecutelimit.aspect;

import com.ticketflow.constant.LockInfoType;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.handle.RedissonDataHandle;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.ticketflow.repeatexecutelimit.constant.RepeatExecuteLimitConstant.PREFIX_NAME;
import static com.ticketflow.repeatexecutelimit.constant.RepeatExecuteLimitConstant.SUCCESS_FLAG;

/**
 * 两层防重锁设计：
 * 第一层 — 本地 ReentrantLock（快速失败，同 JVM 内并发）
 * 第二层 — Redis Reentrant Lock + tryLock(0)（非阻塞快速失败，跨进程互斥）
 *
 * @Order(-11) 确保在 @Transactional 之前获取锁。
 */
@Slf4j
@Aspect
@Order(-11)
@AllArgsConstructor
public class RepeatExecuteLimitAspect {

    private final LocalLockCache localLockCache;

    private final LockInfoHandleFactory lockInfoHandleFactory;

    private final ServiceLockFactory serviceLockFactory;

    private final RedissonDataHandle redissonDataHandle;


    @Around("@annotation(repeatLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        return executeWithLimit(joinPoint, repeatLimit);
    }

    /**
     * 类级 @RepeatExecuteLimit 支持：类注解作为默认配置；方法上已有 @RepeatExecuteLimit 时直接放行，
     * 交由方法级切面处理（避免双重加锁），语义与 @Transactional 一致（方法级覆盖类级）。
     */
    @Around("@within(repeatLimit)")
    public Object aroundClass(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        if (isMethodAnnotated(joinPoint)) {
            return joinPoint.proceed();
        }
        return executeWithLimit(joinPoint, repeatLimit);
    }

    private boolean isMethodAnnotated(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (signatureMethod.isAnnotationPresent(RepeatExecuteLimit.class)) {
            return true;
        }
        try {
            Method targetMethod = joinPoint.getTarget().getClass()
                    .getMethod(signatureMethod.getName(), signatureMethod.getParameterTypes());
            return targetMethod.isAnnotationPresent(RepeatExecuteLimit.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private Object executeWithLimit(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("repeatLimit:{} 在已开启的事务内获取，幂等标记写入可能早于事务提交；请将 @RepeatExecuteLimit 与 @Transactional 标注在同一方法",
                    repeatLimit.name());
        }
        //指定保持幂等的时间
        long durationTime = repeatLimit.durationTime();
        //提示信息
        String message = repeatLimit.message();
        Object obj;
        //获取锁信息
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.REPEAT_EXECUTE_LIMIT);
        //解析锁名字
        String lockName = lockInfoHandle.getLockName(joinPoint, repeatLimit.name(), repeatLimit.keys());
        //幂等标识
        String repeatFlagName = PREFIX_NAME + lockName;
        //durationTime=0 时不写幂等标记，GET 恒为 null；跳过幂等标识查询以省 2 次 Redis 往返，
        //仍保留本地锁 + 分布式锁防并发（语义与原来完全等价）
        boolean needIdempotentCheck = durationTime > 0;
        //获得幂等标识
        if (needIdempotentCheck) {
            String flagObject = redissonDataHandle.get(repeatFlagName);
            //如果幂等标识的值为success，说明已经有请求在执行了，这次请求直接结束
            if (SUCCESS_FLAG.equals(flagObject)) {
                throw new TicketFlowFrameException(message);
            }
        }
        //获取本地锁
        ReentrantLock localLock = localLockCache.getLock(lockName, false);
        //本地锁获取锁
        boolean localLockResult = localLock.tryLock();
        //如果上锁失败，说明已经有请求在执行了，这次请求直接结束
        if (!localLockResult) {
            throw new TicketFlowFrameException(message);
        }
        try {
            //获取分布式锁
            ServiceLocker lock = serviceLockFactory.getLock(LockType.Reentrant);
            //分布式锁获取锁
            boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, 0);
            //加锁成功执行
            if (result) {
                try {
                    //再次获取幂等标识
                    if (needIdempotentCheck) {
                        String flagObject = redissonDataHandle.get(repeatFlagName);
                        //如果幂等标识的值为success，说明已经有请求在执行了，这次请求直接结束
                        if (SUCCESS_FLAG.equals(flagObject)) {
                            throw new TicketFlowFrameException(message);
                        }
                    }
                    //执行业务逻辑
                    obj = joinPoint.proceed();
                    if (durationTime > 0) {
                        try {
                            //业务逻辑执行成功 并且 指定了设置幂等保持时间 设置请求标识
                            redissonDataHandle.set(repeatFlagName, SUCCESS_FLAG, durationTime, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            log.error("getBucket error", e);
                        }
                    }
                    return obj;
                } finally {
                    lock.unlock(lockName);
                }
            } else {
                //获取锁失败，说明已经有请求在执行了，这次请求直接结束
                throw new TicketFlowFrameException(message);
            }
        } finally {
            localLock.unlock();
        }
    }
}