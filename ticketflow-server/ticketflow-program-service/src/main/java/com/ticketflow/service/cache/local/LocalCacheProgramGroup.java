package com.ticketflow.service.cache.local;

import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramGroupVo;
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
 * 节目分组本地缓存（Caffeine）。
 * 缓存节目分组数据，按最近场次时间自动过期，用于节目列表聚合展示。
 * <p>
 * 配合 Redis Stream 的 delLocalCache() 实现主动失效。
 */
@Component
public class LocalCacheProgramGroup {
    
    /**
     * Caffeine 本地缓存实例，key=分组ID，value=节目分组详情
     */
    private Cache<String, ProgramGroupVo> localCache;
    
    /**
     * 本地缓存最大容量
     */
    @Value("${maximumSize:10000}")
    private Long maximumSize;
    
    /**
     * 初始化 Caffeine 缓存，过期时间 = 最近场次时间 - 当前时间。
     * 更新/读取不重置过期时间，最近的场次结束后缓存自动失效。
     */
    @PostConstruct
    public void localLockCacheInit(){
        localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, ProgramGroupVo>() {
                    @Override
                    public long expireAfterCreate(@NonNull final String key, @NonNull final ProgramGroupVo value,
                                                  final long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos
                                (DateUtils.countBetweenSecond(DateUtils.now(),value.getRecentShowTime()));
                    }
                    
                    @Override
                    public long expireAfterUpdate(@NonNull final String key, @NonNull final ProgramGroupVo value,
                                                  final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                    
                    @Override
                    public long expireAfterRead(@NonNull final String key, @NonNull final ProgramGroupVo value,
                                                final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }
    
    /**
     * 获取缓存，不存在时通过 function 加载并回填（线程安全）。
     *
     * @param id       分组ID
     * @param function 加载回调
     * @return 节目分组详情
     */
    public ProgramGroupVo getCache(String id, Function<String, ProgramGroupVo> function){
        return localCache.get(id,function);
    }
    
    /**
     * 主动失效指定分组缓存。
     *
     * @param id 分组ID
     */
    public void del(String id){
        localCache.invalidate(id);
    }
}
