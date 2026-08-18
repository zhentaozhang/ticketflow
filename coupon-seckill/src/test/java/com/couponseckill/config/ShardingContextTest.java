package com.couponseckill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 分表上下文单元测试：路由计算 / 表名拼接 / ThreadLocal 隔离。
 */
class ShardingContextTest {

    @Test
    @DisplayName("按 user_id 取模分片")
    void shardCalculation() {
        assertEquals(0, ShardingContext.shard(0L));
        assertEquals(1, ShardingContext.shard(1L));
        assertEquals(0, ShardingContext.shard(2L));
        assertEquals(1, ShardingContext.shard(3L));
        // 负数用户 ID（理论不存在，但路由要健壮）
        assertEquals(0, ShardingContext.shard(-4L));
        assertEquals(1, ShardingContext.shard(-3L));
    }

    @Test
    @DisplayName("逻辑表名拼接为物理分片表名")
    void shardTableName() {
        assertEquals("flash_sale_order_1", ShardingContext.shardTable("flash_sale_order", 1001L));
        assertEquals("user_coupon_0", ShardingContext.shardTable("user_coupon", 1000L));
    }

    @Test
    @DisplayName("ThreadLocal 设置与清理")
    void setClearIsolation() {
        assertNull(ShardingContext.getUserId());
        ShardingContext.setUserId(42L);
        assertEquals(42L, ShardingContext.getUserId());
        ShardingContext.clear();
        assertNull(ShardingContext.getUserId());
    }

    @Test
    @DisplayName("不同线程互不干扰（防线程池串号）")
    void threadIsolation() throws Exception {
        ShardingContext.setUserId(100L);
        Thread t = new Thread(() -> {
            assertNull(ShardingContext.getUserId(), "子线程不应看到父线程的 userId");
            ShardingContext.setUserId(200L);
            assertEquals(200L, ShardingContext.getUserId());
        });
        t.start();
        t.join();
        assertEquals(100L, ShardingContext.getUserId(), "父线程值不应被子线程修改");
        ShardingContext.clear();
    }
}
