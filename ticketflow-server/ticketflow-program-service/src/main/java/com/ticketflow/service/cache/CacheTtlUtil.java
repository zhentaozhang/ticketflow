package com.ticketflow.service.cache;

import com.ticketflow.util.DateUtils;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存存活时间与雪崩防范工具类。
 * <p>
 * 解决缓存雪崩（Cache Avalanche）的核心手段之一：过期时间随机抖动（Jitter）。
 * 针对同一演出/场次下的详情、分组、票档、座位等多个关联 Key，若直接使用
 * {@code DateUtils.countBetweenSecond(now, showTime)}，会导致它们在开演时刻同一秒批量过期。
 * <p>
 * 本工具类通过在业务到期剩余时间上叠加随机浮动（Jitter），将大量 Key 的自然到期时间平滑打散，
 * 同时严格限制不过期超过演出开始时间（只向下扰动，不向上延期）。
 */
public final class CacheTtlUtil {

    /**
     * 临近演出的阈值（秒）。小于该值时不再叠加抖动，避免过度缩短有效期或引发异常。
     */
    private static final long CRITICAL_EXPIRE_SECONDS = 60L;

    /**
     * 随机抖动最大幅度上限（秒）。避免当距离演出开始很长（如数周）时随机出过大的浮动时间。
     */
    private static final long MAX_JITTER_SECONDS = 300L;

    /**
     * 随机抖动比例（5% ~ 10%）。
     */
    private static final double MIN_JITTER_RATIO = 0.05;
    private static final double MAX_JITTER_RATIO = 0.10;

    private CacheTtlUtil() {
    }

    /**
     * 计算带随机抖动的 Redis 缓存过期时间（秒）。
     *
     * @param expireDate 业务截止时间（如演出开始时间）
     * @return 实际应该设置的 Redis TTL（秒），确保大于等于 0
     */
    public static long calculateExpireSeconds(Date expireDate) {
        if (Objects.isNull(expireDate)) {
            return 0L;
        }
        return calculateExpireSeconds(DateUtils.now(), expireDate);
    }

    /**
     * 计算带随机抖动的 Redis 缓存过期时间（秒），支持指定基准时间（便于单测）。
     *
     * @param baseDate   基准时间（通常为当前时间）
     * @param expireDate 业务截止时间
     * @return 实际应该设置的 Redis TTL（秒）
     */
    public static long calculateExpireSeconds(Date baseDate, Date expireDate) {
        if (Objects.isNull(baseDate) || Objects.isNull(expireDate)) {
            return 0L;
        }
        long baseSeconds = DateUtils.countBetweenSecond(baseDate, expireDate);
        if (baseSeconds <= 0) {
            return 0L;
        }
        if (baseSeconds <= CRITICAL_EXPIRE_SECONDS) {
            return baseSeconds;
        }

        // 计算抖动范围：取 baseSeconds 的 5% ~ 10%，上限不超过 MAX_JITTER_SECONDS（300s）
        long maxJitter = Math.min(MAX_JITTER_SECONDS, Math.max(10L, (long) (baseSeconds * MAX_JITTER_RATIO)));
        long minJitter = Math.min(maxJitter / 2, Math.max(5L, (long) (baseSeconds * MIN_JITTER_RATIO)));

        long jitter = (maxJitter > minJitter)
                ? ThreadLocalRandom.current().nextLong(minJitter, maxJitter + 1)
                : minJitter;

        // 向下扣减抖动时间：保证缓存绝不会在演出开始之后仍然存活，同时错开过期时刻
        long resultTtl = baseSeconds - jitter;
        return Math.max(CRITICAL_EXPIRE_SECONDS, resultTtl);
    }
}
