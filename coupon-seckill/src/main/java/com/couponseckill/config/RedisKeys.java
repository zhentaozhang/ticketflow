package com.couponseckill.config;

/**
 * Redis Key 统一规划（对应 docs/01-技术设计.md §6.1）。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 活动元数据（String JSON: {"startTs":..,"endTs":..,"limit":..}），供 Lua 脚本校验 */
    public static String meta(Long activityId) {
        return "flash:meta:" + activityId;
    }

    /** 剩余库存（String，Lua DECR 原子扣减） */
    public static String stock(Long activityId) {
        return "flash:stock:" + activityId;
    }

    /** 用户已抢计数（String，限购） */
    public static String limit(Long activityId, Long userId) {
        return "flash:limit:" + activityId + ":" + userId;
    }

    /** 请求幂等标记（String，SETNX + TTL） */
    public static String dedup(Long activityId, Long userId, String requestId) {
        return "flash:dedup:" + activityId + ":" + userId + ":" + requestId;
    }

    /** 抢购结果回写（String：SUCCESS|FAIL 明细） */
    public static String result(Long userId, Long activityId) {
        return "flash:result:" + userId + ":" + activityId;
    }

    /** 锁券标记（String，SETNX + TTL，值为锁定订单号） */
    public static String couponLock(String couponNo) {
        return "coupon:lock:" + couponNo;
    }
}
