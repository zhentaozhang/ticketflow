package com.ticketflow.service.cache.local;

import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramVo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 节目详情本地缓存（Caffeine）。
 * 过期策略：取“演出开始时间”和“本地缓存最长存活时间”里较小的那个（见 {@link LocalCacheTtl}）。
 * 配合 Redis Stream 的 delLocalCache() 实现主动失效。
 * <p>
 * ProgramService.getById() 的三级缓存之一：
 * BloomFilter → LocalCacheProgram → Redis
 */
@Component
public class LocalCacheProgram {

    /**
     * Caffeine 本地缓存实例，key=programId，value=节目详情
     */
    private Cache<String, ProgramVo> localCache;


    /**
     * 本地缓存的容量
     *
     */
    @Value("${maximumSize:10000}")  // 默认10000，可在yml中覆盖
    private Long maximumSize;

    /**
     * 初始化 Caffeine 缓存，按演出开始时间动态计算过期时长（并受本地缓存上界限制）。
     * 更新/读取不重置过期时间。
     */
    @PostConstruct
    public void localLockCacheInit() {
        localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, ProgramVo>() {
                    @Override
                    public long expireAfterCreate(@NonNull final String key, @NonNull final ProgramVo value,
                                                  final long currentTime) {
                        // 业务上的自然到期：演出开始后就没人看了；
                        // 但仍然要受“本地缓存最长存活时间”封顶——失效通知是尽力而为的，
                        // 漏一条最多让数据旧 LocalCacheTtl.CACHE_TTL_CAP_SECONDS 秒。
                        // （顺带修了一个单位 bug：countBetweenSecond 返回的是秒，
                        //   原来用 MILLISECONDS.toNanos 包它，等于把秒当毫秒，TTL 小了 1000 倍。）
                        return LocalCacheTtl.capNanos(
                                DateUtils.countBetweenSecond(DateUtils.now(), value.getShowTime()));
                    }

                    @Override
                    public long expireAfterUpdate(@NonNull final String key, @NonNull final ProgramVo value,
                                                  final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration; // 更新不重置
                    }

                    @Override
                    public long expireAfterRead(@NonNull final String key, @NonNull final ProgramVo value,
                                                final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration; // 读取不重置
                    }
                })
                .build();
    }

    /**
     * 获取缓存，若不存在则通过 function 加载并回填（线程安全）。
     *
     * @param id       programId
     * @param function 加载数据的回调（一般从 Redis/DB 读取）
     * @return 节目详情
     */
    public ProgramVo getCache(String id, Function<String, ProgramVo> function) {
        return localCache.get(id, function);
    }

    /**
     * 仅查询缓存，不存在返回 null，不触发加载。
     *
     * @param id programId
     * @return 节目详情，或 null
     */
    public ProgramVo getCache(String id) {
        return localCache.getIfPresent(id);
    }

    /**
     * 主动失效指定节目缓存（配合 Redis Stream 消息使用）。
     *
     * @param id programId
     */
    public void del(String id) {
        localCache.invalidate(id);
    }
}
