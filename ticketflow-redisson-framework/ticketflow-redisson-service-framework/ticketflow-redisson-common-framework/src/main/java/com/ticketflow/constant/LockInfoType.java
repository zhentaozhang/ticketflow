package com.ticketflow.constant;

/**
 * 锁信息类型常量——区分分布式锁和防重复执行的锁名前缀。
 *
 *   SERVICE_LOCK         → 用于 @ServiceLock 注解
 *   REPEAT_EXECUTE_LIMIT → 用于 @RepeatExecuteLimit 注解
 *
 * LockInfoHandleFactory 根据此类型从 Spring 容器获取对应的 Handle
 */
public class LockInfoType {
    
    /***
     * 防重复执行幂等
     */
    public static final String REPEAT_EXECUTE_LIMIT = "repeat_execute_limit";
    
    /***
     * 分布式锁
     */
    public static final String SERVICE_LOCK = "service_lock";
    
}
