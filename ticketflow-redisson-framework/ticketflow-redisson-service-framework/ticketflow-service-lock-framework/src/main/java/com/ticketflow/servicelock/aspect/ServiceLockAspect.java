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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        return lockAndProceed(joinPoint, servicelock);
    }

    /**
     * 类级 @ServiceLock 支持：类注解作为默认锁配置；方法上已有 @ServiceLock 时直接放行，
     * 交由方法级切面处理（避免双重加锁），语义与 @Transactional 一致（方法级覆盖类级）。
     */
    @Around("@within(servicelock)")
    public Object aroundClass(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        if (isMethodAnnotated(joinPoint)) {
            return joinPoint.proceed();
        }
        return lockAndProceed(joinPoint, servicelock);
    }

    private boolean isMethodAnnotated(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (signatureMethod.isAnnotationPresent(ServiceLock.class)) {
            return true;
        }
        try {
            Method targetMethod = joinPoint.getTarget().getClass()
                    .getMethod(signatureMethod.getName(), signatureMethod.getParameterTypes());
            return targetMethod.isAnnotationPresent(ServiceLock.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private Object lockAndProceed(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("serviceLock:{} 在已开启的事务内获取，锁释放可能早于事务提交，存在竞态；请将 @ServiceLock 与 @Transactional 标注在同一方法",
                    servicelock.name());
        }
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
