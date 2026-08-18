package com.couponseckill.config;

import lombok.extern.slf4j.Slf4j;

/**
 * 简易分表上下文：在 Service 层调用分表 Mapper 前设置 userId，
 * MyBatis-Plus 的 DynamicTableNameInnerInterceptor 据此路由到 {table}_{userId % 2}。
 * 务必在 finally 中清理，避免线程池串号。
 */
@Slf4j
public final class ShardingContext {

    public static final int SHARD_COUNT = 2;

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private ShardingContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static int shard(Long userId) {
        return (int) (Math.abs(userId) % SHARD_COUNT);
    }

    public static String shardTable(String logicTable, Long userId) {
        return logicTable + "_" + shard(userId);
    }

    public static void clear() {
        USER_ID.remove();
    }
}
