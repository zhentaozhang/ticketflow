package com.ticketflow.servicelock.annotion;

import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.info.LockTimeOutStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解——声明在方法或类上，自动加锁/解锁。
 *
 * name:    业务名（分布式锁 key 前缀）
 * keys:    SpEL 表达式，支持 #paramName 引用方法参数
 * lockType:  Reentrant / Fair / Read / Write
 * waitTime:  获取锁等待时间，超时执行 lockTimeoutStrategy
 * lockTimeoutStrategy: 超时策略（目前仅 FAIL）
 *
 * 支持类级声明：类注解作为默认配置，方法级注解优先覆盖（同 @Transactional 语义）。
 * 锁与事务时序：锁先于事务开启、释放晚于事务提交的保证仅在加锁方法上
 * 同时标注 @Transactional 时成立（切面 @Order(-10) 低于事务拦截器默认顺序）。
 * 若 @Transactional 标注在更外层方法或发生自调用，锁可能在事务提交前释放。
 */
@Target(value= {ElementType.TYPE, ElementType.METHOD})
@Retention(value= RetentionPolicy.RUNTIME)
public @interface ServiceLock {

    /**
     * 锁的类型(默认 可重入锁)
     * */
    LockType lockType() default LockType.Reentrant;
    
    /**
     * 业务名称
     * @return name
     */
    String name() default "";
    /**
     * 自定义业务key
     * @return keys
     */
    String [] keys();

    /**
     * 尝试加锁失败最多等待时间
     * @return waitTime
     */
    long waitTime() default 10;

    /**
     * 时间单位
     * @return TimeUnit
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 加锁超时的处理策略
     * @return LockTimeOutStrategy
     */
    LockTimeOutStrategy lockTimeoutStrategy() default LockTimeOutStrategy.FAIL;

    /**
     * 自定义加锁超时的处理策略
     * @return customLockTimeoutStrategy
     */
    String customLockTimeoutStrategy() default "";
}
