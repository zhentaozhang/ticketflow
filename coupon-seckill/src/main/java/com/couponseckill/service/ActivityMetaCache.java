package com.couponseckill.service;

import com.couponseckill.config.RedisKeys;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

/**
 * 活动元数据本地缓存（Caffeine，TTL 5s）。
 * 仅用于抢购快速失败（未开始/已结束/已下架），权威校验仍在 Lua 脚本内。
 */
@Component
public class ActivityMetaCache {

    private final com.github.benmanes.caffeine.cache.Cache<Long, ActivityMeta> cache;

    public ActivityMetaCache() {
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    public ActivityMeta get(Long activityId) {
        return cache.getIfPresent(activityId);
    }

    public void put(Long activityId, ActivityMeta meta) {
        cache.put(activityId, meta);
    }

    @Data
    @AllArgsConstructor
    public static class ActivityMeta {
        private Integer status;
        private Long startTs;
        private Long endTs;
    }
}
