/**
 * @ServiceLock 注解的 AOP 实现——自动加锁 / 解锁 / 超时处理。
 *
 * 流程：解析 @ServiceLock → LockInfoHandleFactory 获取锁信息 →
 *       ServiceLockFactory 获取对应锁类型 → lock() → 执行业务 → unlock()
 *
 * 锁等待超时时委托 LockTimeOutStrategy 处理（当前为快速失败）
 */
package com.ticketflow.servicelock.aspect;

import com.ticketflow.constant.LockInfoType;
import com.ticketflow.util.StringUtil;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 **/
@Slf4j
@Aspect
@Order(-10)
@AllArgsConstructor
public class ServiceLockAspect {
    
    private final LockInfoHandleFactory lockInfoHandleFactory;
    
    private final ServiceLockFactory serviceLockFactory;
    

    @Around("@annotation(servicelock)")
    public Object around(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        String lockName = lockInfoHandle.getLockName(joinPoint, servicelock.name(),servicelock.keys());
        LockType lockType = servicelock.lockType();
        long waitTime = servicelock.waitTime();
        TimeUnit timeUnit = servicelock.timeUnit();

        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        boolean result = lock.tryLock(lockName, timeUnit, waitTime);

        if (result) {
            try {
                return joinPoint.proceed();
            }finally{
                lock.unlock(lockName);
            }
        }else {
            log.warn("Timeout while acquiring serviceLock:{}",lockName);
            String customLockTimeoutStrategy = servicelock.customLockTimeoutStrategy();
            if (StringUtil.isNotEmpty(customLockTimeoutStrategy)) {
                return handleCustomLockTimeoutStrategy(customLockTimeoutStrategy, joinPoint);
            }
            servicelock.lockTimeoutStrategy().handler(lockName);
            // 兜底：即使超时策略实现不抛异常，也绝不在未持有锁的情况下执行业务
            throw new RuntimeException(lockName + "请求频繁");
        }
    }

    public Object handleCustomLockTimeoutStrategy(String customLockTimeoutStrategy,JoinPoint joinPoint) {
        // prepare invocation context
        Method currentMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Method handleMethod = null;
        try {
            handleMethod = target.getClass().getDeclaredMethod(customLockTimeoutStrategy, currentMethod.getParameterTypes());
            handleMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Illegal annotation param customLockTimeoutStrategy :" + customLockTimeoutStrategy,e);
        }
        Object[] args = joinPoint.getArgs();

        // invoke
        Object result;
        try {
            result = handleMethod.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Fail to illegal access custom lock timeout handler: " + customLockTimeoutStrategy ,e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Fail to invoke custom lock timeout handler: " + customLockTimeoutStrategy ,e);
        }
        return result;
    }
}
