package com.ticketflow.lockinfo;

import org.aspectj.lang.JoinPoint;

/**
 * 锁信息获取接口——由 @ServiceLock / @RepeatExecuteLimit 切面调用。
 *
 * getLockInfo(joinPoint, name, keys) 负责从注解参数和 SpEL 表达式
 * 中解析出实际的锁名，返回 LockInfoHandle 供后续加锁使用
 */
public interface LockInfoHandle {
    /**
     * 获取锁信息
     * @param joinPoint 切面
     * @param name 锁业务名
     * @param keys 锁
     * @return 锁信息
     * */
    String getLockName(JoinPoint joinPoint, String name, String[] keys);
    
    /**
     * 拼装锁信息
     * @param name 锁业务名
     * @param keys 锁
     * @return 锁信息
     * */
    String simpleGetLockName(String name,String[] keys);
}
