package com.ticketflow.repeatexecutelimit.constant;

/**
 * 防重复执行标记常量——Redis 中用于去重的 key 后缀和 value。
 *
 *   PREFIX_NAME = "repeat_flag"
 *   SUCCESS_FLAG = "success"
 *
 * 加锁成功后在 Redis 写入 PREFIX_NAME + 锁名 → SUCCESS_FLAG，并设置 ttl
 */
public class RepeatExecuteLimitConstant {
    
    public static final String PREFIX_NAME = "repeat_flag";
    
    public static final String SUCCESS_FLAG = "success";
}
