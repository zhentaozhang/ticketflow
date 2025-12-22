package com.ticketflow.service.cache.local;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.TicketCategoryVo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 票档本地缓存（Caffeine）。
 * 缓存节目票档数据，过期时间与 Redis 中的 TTL 对齐，用于快速获取票价和余量。
 * <p>
 * 配合 Redis Stream 的 delLocalCache() 实现主动失效。
 */
@Component
public class LocalCacheTicketCategory {
    
    /**
     * Caffeine 本地缓存实例，key=节目ID（Long），value=票档列表
     */
    private Cache<Long, List<TicketCategoryVo>> localCache;
    
    /**
     * 本地缓存最大容量
     */
    @Value("${maximumSize:10000}")
    private Long maximumSize;
    
    @Autowired
    private RedisCache redisCache;
    
    /**
     * 初始化 Caffeine 缓存，过期时间与 Redis 中票档数据的 TTL 保持一致。
     * 读取/更新不重置过期时间。
     */
    @PostConstruct
    public void localLockCacheInit(){
        localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<Long, List<TicketCategoryVo>>() {
                    @Override
                    public long expireAfterCreate(@NonNull final Long key, @NonNull final List<TicketCategoryVo> value,
                                                  final long currentTime) {
                        Long expire = redisCache.getExpire(RedisKeyBuild.createRedisKey
                                (RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, key),TimeUnit.MILLISECONDS);
                        return TimeUnit.MILLISECONDS.toNanos(expire);
                    }
                    
                    @Override
                    public long expireAfterUpdate(@NonNull final Long key, @NonNull final List<TicketCategoryVo> value,
                                                  final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                    
                    @Override
                    public long expireAfterRead(@NonNull final Long key, @NonNull final List<TicketCategoryVo> value,
                                                final long currentTime, @NonNegative final long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }
    
    /**
     * 获取缓存，不存在时通过 function 加载并回填（线程安全）。
     *
     * @param id       节目ID
     * @param function 加载回调
     * @return 票档列表
     */
    public List<TicketCategoryVo> getCache(Long id, Function<Long, List<TicketCategoryVo>> function){
        return localCache.get(id,function);
    }
    
    /**
     * 主动失效指定节目的票档缓存。
     *
     * @param id 节目ID
     */
    public void del(Long id){
        localCache.invalidate(id);
    }
}
