package com.ticketflow.service.cache.local;

import com.ticketflow.entity.ProgramCategory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.function.Function;

/**
 * 节目分类本地缓存（Caffeine）。
 * 缓存节目分类数据（如演唱会、音乐会等），减少数据库和 Redis 查询。
 * 无自定义过期策略，使用 Caffeine 默认的基于大小淘汰。
 */
@Component
public class LocalCacheProgramCategory {

    /**
     * Caffeine 本地缓存实例，key=分类编码，value=节目分类
     */
    private Cache<String, ProgramCategory> localCache;

    /**
     * 初始化 Caffeine 缓存，仅设置默认大小淘汰策略。
     */
    @PostConstruct
    public void localLockCacheInit() {
        localCache = Caffeine.newBuilder().build();
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
