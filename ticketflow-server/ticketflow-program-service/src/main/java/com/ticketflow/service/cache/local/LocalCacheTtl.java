package com.ticketflow.service.cache.local;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存的存活时间策略。
 * <p>
 * 背景：本地缓存散在各个节点上，数据变更只能靠 Redis Stream 广播"这份缓存失效了"来收敛。
 * 而这个广播是<b>尽力而为</b>的——它可能会漏（Redis 自身丢数据、节点与 Redis 网络中断期间的消息没拿到，
 * 等等），任何一种"通知机制"都不该被假设成 100% 送达。
 * <p>
 * 所以本地缓存必须有一个<b>不依赖通知</b>的上界：不管有没有收到失效消息，
 * 一份本地缓存最多活 {@link #CACHE_TTL_CAP_SECONDS} 秒。
 * <p>
 * 这样最坏后果就被封住了：<b>漏一条失效通知，数据最多旧 5 分钟</b>，
 * 而不是"一直旧到演出开始"。代价是每个热点节目每 5 分钟从 Redis 重新加载一次——
 * 这些数据本来就是读多写少、允许短暂不一致的运营数据（见 07），这笔账很划算。
 */
public final class LocalCacheTtl {

    /**
     * 本地缓存最长存活时间（秒）。
     * <p>
     * 业务上的"自然到期时间"（比如到演出开始）如果比它短，就取业务那个（{@link #cap} 取的是较小值）。
     */
    public static final long CACHE_TTL_CAP_SECONDS = 300L;

    private LocalCacheTtl() {
    }

    /**
     * 取"业务到期时间"和"本地缓存上界"里较小的那个。
     *
     * @param businessExpireSeconds 业务意义上的剩余存活秒数（例如离演出开始还有多少秒）
     * @return 实际应该用的存活秒数，且保证非负
     */
    public static long cap(long businessExpireSeconds) {
        if (businessExpireSeconds <= 0) {
            // 已经到期、或者拿到的是"没有过期时间/键不存在"这类无意义的值：直接当作立即过期，
            // 让本地缓存不要留一份寿命未知的副本
            return 0L;
        }
        return Math.min(businessExpireSeconds, CACHE_TTL_CAP_SECONDS);
    }

    /**
     * {@link #cap(long)} 的纳秒版本，给 Caffeine 的 {@code Expiry} 用。
     */
    public static long capNanos(long businessExpireSeconds) {
        return TimeUnit.SECONDS.toNanos(cap(businessExpireSeconds));
    }
}
