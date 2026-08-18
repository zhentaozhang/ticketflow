package com.couponseckill.service;

import com.couponseckill.config.RedisKeys;
import com.couponseckill.entity.FlashSaleActivity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 活动缓存服务：预热 / 清理 / 读取 Redis 中的活动元数据与库存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 预热：写活动元数据（供 Lua 校验时间窗与限购）+ 初始化库存。
     * 发布时调用；重复预热以最新配置为准。
     */
    public void warmUp(FlashSaleActivity activity) {
        String metaJson = buildMetaJson(activity);
        redisTemplate.opsForValue().set(RedisKeys.meta(activity.getId()), metaJson);
        // 库存以 DB 权威值为准初始化（发布时刻的快照）
        redisTemplate.opsForValue().set(RedisKeys.stock(activity.getId()),
                String.valueOf(activity.getStock()));
        log.info("[warm-up] activityId={} stock={} meta={}", activity.getId(), activity.getStock(), metaJson);
    }

    /** 紧急下架/活动结束清理：删除 meta 与 stock（抢购脚本因 meta 缺失返回 -3 活动不可抢） */
    public void remove(Long activityId) {
        redisTemplate.delete(RedisKeys.meta(activityId));
        redisTemplate.delete(RedisKeys.stock(activityId));
        log.info("[cache-remove] activityId={}", activityId);
    }

    /** 管理端调整库存：DB 乐观锁成功后，同步修正 Redis 库存 */
    public void adjustStock(Long activityId, int delta) {
        redisTemplate.opsForValue().increment(RedisKeys.stock(activityId), delta);
    }

    /** 读取活动元数据（用于管理端校验/调试） */
    public String getMetaJson(Long activityId) {
        return redisTemplate.opsForValue().get(RedisKeys.meta(activityId));
    }

    private String buildMetaJson(FlashSaleActivity activity) {
        Map<String, Object> meta = Map.of(
                "startTs", activity.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                "endTs", activity.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                "limit", activity.getPerUserLimit());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize meta failed", e);
        }
    }

    public boolean stockKeyExists(Long activityId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.stock(activityId)));
    }

    public Long getStock(Long activityId) {
        String v = redisTemplate.opsForValue().get(RedisKeys.stock(activityId));
        return v == null ? null : Long.parseLong(v);
    }

    public void setStock(Long activityId, long stock) {
        redisTemplate.opsForValue().set(RedisKeys.stock(activityId), String.valueOf(stock),
                7, TimeUnit.DAYS);
    }
}
