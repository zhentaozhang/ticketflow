package com.ticketflow.service.cache;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存防雪崩工具类测试：验证随机抖动（Jitter）与边界条件。
 */
class CacheTtlUtilTest {

    @Test
    void 临近演出时不加抖动() {
        Date now = new Date();
        // 剩余 30 秒（小于临界值 60 秒）
        Date expireDate = new Date(now.getTime() + TimeUnit.SECONDS.toMillis(30));

        long ttl = CacheTtlUtil.calculateExpireSeconds(now, expireDate);
        assertEquals(30L, ttl, "临近演出的 30 秒应直接返回原值，不减小时间");
    }

    @Test
    void 远期演出添加抖动且绝不超出业务截止时间() {
        Date now = new Date();
        // 1 小时后演出（3600 秒）
        Date expireDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(1));

        long baseSeconds = 3600L;
        for (int i = 0; i < 100; i++) {
            long ttl = CacheTtlUtil.calculateExpireSeconds(now, expireDate);
            // 抖动为 150 ~ 300 秒，TTL 范围应在 [3300, 3450]
            assertTrue(ttl < baseSeconds, "TTL 必须小于基础存活时间，确保不会在演出开始后继续存活");
            assertTrue(ttl >= 3300 && ttl <= 3450, "TTL 应在抖动后的合理区间内: " + ttl);
        }
    }

    @Test
    void 过去时间或异常参数安全防护() {
        Date now = new Date();
        Date past = new Date(now.getTime() - TimeUnit.HOURS.toMillis(1));

        assertEquals(0L, CacheTtlUtil.calculateExpireSeconds(null));
        assertEquals(0L, CacheTtlUtil.calculateExpireSeconds(now, null));
        assertEquals(0L, CacheTtlUtil.calculateExpireSeconds(null, now));
        assertEquals(0L, CacheTtlUtil.calculateExpireSeconds(now, past), "过去时间应返回 0，立即过期");
    }

    @Test
    void 多次计算存在随机散列() {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(2));

        // 验证 50 次调用中不会全是完全相同的秒数
        boolean hasVariation = false;
        long firstTtl = CacheTtlUtil.calculateExpireSeconds(now, expireDate);
        for (int i = 0; i < 50; i++) {
            long ttl = CacheTtlUtil.calculateExpireSeconds(now, expireDate);
            if (ttl != firstTtl) {
                hasVariation = true;
                break;
            }
        }
        assertTrue(hasVariation, "连续计算应存在随机抖动，避免多个相同演出的 key 在同一秒失效");
    }
}
