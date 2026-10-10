package com.ticketflow.service.cache.local;

import com.ticketflow.entity.ProgramCategory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 节目分类本地缓存（Caffeine）。
 * <p>
 * 缓存节目分类数据（如演唱会、音乐会等），减少数据库和 Redis 查询。
 * <p>
 * 过期策略：和别的本地缓存一样受 {@link LocalCacheTtl} 封顶。
 * <p>
 * 注意这个缓存和别的不一样：它<b>不在失效广播的范围内</b>——
 * 广播里带的是 programId，而它按“分类编码”缓存（分类与节目是多对一），
 * delLocalCache 也只清节目/分组/场次/票档四个缓存。
 * 所以 TTL 是它唯一的收敛手段（以前连 TTL 都没有：
 * {@code Caffeine.newBuilder().build()} 既无容量上限、也无过期时间，
 * 而 Caffeine 的默认就是无上限 + 永不过期，所以分类改名后各节点会一直拿旧值。
 * 当时注释写的“使用 Caffeine 默认的基于大小淘汰”也是不成立的：默认没有大小限制。)
 */
@Component
public class LocalCacheProgramCategory {

    /**
     * Caffeine 本地缓存实例，key=分类编码，value=节目分类
     */
    private Cache<String, ProgramCategory> localCache;

    /**
     * 本地缓存的容量上限
     */
    @Value("${maximumSize:10000}")
    private Long maximumSize;

    /**
     * 初始化 Caffeine 缓存：容量上限 + 最长存活时间。
     */
    @PostConstruct
    public void localLockCacheInit() {
        localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(LocalCacheTtl.CACHE_TTL_CAP_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取缓存，不存在时通过 function 加载并回填（线程安全）。
     *
     * @param id       分类编码
     * @param function 加载回调
     * @return 节目分类
     */
    public ProgramCategory get(String id, Function<String, ProgramCategory> function) {
        return localCache.get(id, function);
    }
}
