package com.ticketflow.service.cache.local;

import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.util.DateUtils;
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
 * 节目演出时间本地缓存（Caffeine）。
 * 缓存节目场次数据，按演出开始时间自动过期，用于快速获取演出时间。
 * <p>
 * 配合 Redis Stream 的 delLocalCache() 实现主动失效。
 */
@Component
public class LocalCacheProgramShowTime {
    
    /**
     * Caffeine 本地缓存实例，key=场次ID，value=演出时间
     */
    private Cache<String, ProgramShowTime> localCache;
    
    /**
     * 本地缓存最大容量
     */
    @Value("${maximumSize:10000}")
    private Long maximumSize;
    
    /**
     * 初始化 Caffeine 缓存，过期时间 = 演出开始时间 - 当前时间。
     * 更新/读取不重置过期时间，演出结束后缓存自动失效。
     */
    @PostConstruct
    public void localLockCacheInit(){
        localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, ProgramShowTime>() {
                    @Override
                    public long expireAfterCreate(@NonNull final String key, @NonNull final ProgramShowTime value, 
                                                  final long currentTime) {
                        return TimeUnit.SECONDS.toNanos(DateUtils.countBetweenSecond(DateUtils.now(),value.getShowTime()));
                    }
                    
                    @Override
                    public long expireAfterUpdate(@NonNull final String key, @NonNull final ProgramShowTime value, 
                                                  final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                    
                    @Override
                    public long expireAfterRead(@NonNull final String key, @NonNull final ProgramShowTime value, 
                                                final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }
    
    /**
     * 获取缓存，不存在时通过 function 加载并回填（线程安全）。
     *
     * @param id       场次ID
     * @param function 加载回调
     * @return 演出时间对象
     */
    public ProgramShowTime getCache(String id, Function<String, ProgramShowTime> function){
        return localCache.get(id,function);
    }
    
    /**
     * 仅查询缓存，不存在返回 null。
     *
     * @param id 场次ID
     * @return 演出时间对象，或 null
     */
    public ProgramShowTime getCache(String id){
        return localCache.getIfPresent(id);
    }
    
    /**
     * 主动失效指定场次缓存。
     *
     * @param id 场次ID
     */
    public void del(String id){
        localCache.invalidate(id);
    }
}
