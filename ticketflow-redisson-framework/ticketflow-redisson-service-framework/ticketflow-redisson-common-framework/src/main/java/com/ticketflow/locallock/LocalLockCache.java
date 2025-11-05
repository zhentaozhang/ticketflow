package com.ticketflow.locallock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地 ReentrantLock 缓存，用于细粒度方法级互斥。
 * 以 Caffeine 作为锁容器——Caffeine.get() 自身线程安全，避免锁创建时的并发覆盖。
 *
 * 生命周期：锁对象在 durationTime（默认 48h）无访问后自动驱逐，
 *           避免内存泄漏。锁不存在时由 Caffeine 自动创建新的 ReentrantLock。
 *
 * 使用场景：
 *   - RepeatExecuteLimitAspect 防重复提交（同一 key 在同一 JVM 内串行化）
 *   - BaseProgramOrder 本地锁层（分布式锁前的第一层屏障，减少 Redis 压力）
 */
public class LocalLockCache {
    
    /**
     * 本地锁缓存
     * */
    private Cache<String, ReentrantLock> localLockCache;
    /**
     * 本地锁的过期时间(小时单位)
     * */
    @Value("${durationTime:48}")
    private Integer durationTime;
    
    @PostConstruct
    public void localLockCacheInit(){
        localLockCache = Caffeine.newBuilder()
                .expireAfterWrite(durationTime, TimeUnit.HOURS)
                .build();
    }
    
    /**
     * 获得锁，Caffeine的get是线程安全的
     * */
    public ReentrantLock getLock(String lockKey,boolean fair){
        return localLockCache.get(lockKey, key -> new ReentrantLock(fair));
    }
}
