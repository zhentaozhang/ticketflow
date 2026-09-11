/**
 * @ServiceLock 注解的 AOP 实现——自动加锁 / 解锁 / 超时处理。
 * <p>
 * 流程：解析 @ServiceLock → LockInfoHandleFactory 获取锁信息 →
 * ServiceLockFactory 获取对应锁类型 → lock() → 执行业务 → unlock()
 * <p>
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
 *
 **/
@Slf4j
@Aspect
@Order(-10)
@AllArgsConstructor
public class ServiceLockAspect {

    private final LockInfoHandleFactory lockInfoHandleFactory;

    private final ServiceLockFactory serviceLockFactory;


    @Around("@annotation(servicelock)")        // ① 拦截"方法上有注解"的调用
    public Object around(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        return lockAndProceed(joinPoint, servicelock);
    }

    @Around("@within(servicelock)")            // ② 拦截"类上有注解"的调用
    public Object aroundClass(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        if (isMethodAnnotated(joinPoint)) {    // 方法自己也有注解？
            return joinPoint.proceed();        // → 放行，交给①处理，避免加两次锁
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
        // ① 防呆：如果发现事务已经开了才来拿锁 → 时序错了，打 warn 提醒
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("serviceLock:{} 在已开启的事务内获取，锁释放可能早于事务提交，存在竞态；请将 @ServiceLock 与 @Transactional 标注在同一方法",
                    servicelock.name());
        }
        // ② 算锁 key
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        String lockName = lockInfoHandle.getLockName(joinPoint, servicelock.name(), servicelock.keys());
        // ③ 读注解配置
        LockType lockType = servicelock.lockType();
        long waitTime = servicelock.waitTime();
        TimeUnit timeUnit = servicelock.timeUnit();
        // ④ 按类型拿锁
        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        // ⑤ 抢锁（tryLock 而非 lock！）
        boolean result = lock.tryLock(lockName, timeUnit, waitTime);

        if (result) {                        // 抢到了
            try {
                return joinPoint.proceed();  // 放行：开事务 → 执行业务
            } finally {
                lock.unlock(lockName);       // 业务（含事务提交）结束后解锁，finally 保证异常也解锁
            }
        } else {                             // 没抢到（等满 waitTime）
            log.warn("Timeout while acquiring serviceLock:{}", lockName);
            String customLockTimeoutStrategy = servicelock.customLockTimeoutStrategy();
            if (StringUtil.isNotEmpty(customLockTimeoutStrategy)) {
                return handleCustomLockTimeoutStrategy(customLockTimeoutStrategy, joinPoint);  // 自定义策略
            }
            servicelock.lockTimeoutStrategy().handler(lockName);   // 默认：FAIL 抛异常
            // 兜底：就算超时策略实现不抛异常，也绝不在没拿到锁的情况下执行业务
            throw new RuntimeException(lockName + "请求频繁");
        }
    }

    public Object handleCustomLockTimeoutStrategy(String customLockTimeoutStrategy, JoinPoint joinPoint) {
        // prepare invocation context
        Method currentMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Method handleMethod = null;
        try {
            handleMethod = target.getClass().getDeclaredMethod(customLockTimeoutStrategy, currentMethod.getParameterTypes());
            handleMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Illegal annotation param customLockTimeoutStrategy :" + customLockTimeoutStrategy, e);
        }
        Object[] args = joinPoint.getArgs();

        // invoke
        Object result;
        try {
            result = handleMethod.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Fail to illegal access custom lock timeout handler: " + customLockTimeoutStrategy, e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Fail to invoke custom lock timeout handler: " + customLockTimeoutStrategy, e);
        }
        return result;
    }
}
