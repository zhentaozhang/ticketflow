package com.ticketflow.service.cache.local;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地缓存 TTL 策略测试。
 * <p>
 * 这一层存在的理由是：失效广播（Redis Stream）是<b>尽力而为</b>的，任何通知机制都不能假设 100% 送达。
 * 所以本地缓存必须有一个不依赖通知的上界——漏一条通知，数据最多旧 {@code CACHE_TTL_CAP_SECONDS} 秒，
 * 而不是"一直旧到演出开始"（演出可能在几十天以后）。
 */
class LocalCacheTtlTest {

    @Test
    void 演出还很远时取上界值() {
        // 30 天 = 2592000 秒，远大于上界
        long thirtyDays = TimeUnit.DAYS.toSeconds(30);

        assertEquals(LocalCacheTtl.CACHE_TTL_CAP_SECONDS, LocalCacheTtl.cap(thirtyDays));
        assertEquals(TimeUnit.SECONDS.toNanos(LocalCacheTtl.CACHE_TTL_CAP_SECONDS),
                LocalCacheTtl.capNanos(thirtyDays));
    }

    @Test
    void 演出更近时取业务时间() {
        long twoMinutes = TimeUnit.MINUTES.toSeconds(2);

        assertEquals(twoMinutes, LocalCacheTtl.cap(twoMinutes));
        assertEquals(TimeUnit.SECONDS.toNanos(twoMinutes), LocalCacheTtl.capNanos(twoMinutes));
    }

    @Test
    void 已过期或拿不到有效时间时立即过期() {
        // 演出已开始（或时间字段异常），以及 Redis getExpire 返回的 -1/-2
        assertEquals(0L, LocalCacheTtl.cap(0L));
        assertEquals(0L, LocalCacheTtl.cap(-1L));
        assertEquals(0L, LocalCacheTtl.cap(-2L));
        assertEquals(0L, LocalCacheTtl.capNanos(-1L));
    }

    @Test
    void 上界本身是个有意义的有限值() {
        assertTrue(LocalCacheTtl.CACHE_TTL_CAP_SECONDS > 0);
        // 上界必须远小于"一场演出可能提前多少天开票"，否则封顶就没意义了（这里按 1 小时以内约束）
        assertTrue(LocalCacheTtl.CACHE_TTL_CAP_SECONDS <= TimeUnit.HOURS.toSeconds(1),
                "本地缓存上界应该明显小于演出的提前期，否则失效通知漏了还是会长期旧");
    }
}
