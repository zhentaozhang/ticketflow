package com.couponseckill.service;

import com.couponseckill.entity.UserCoupon;
import com.couponseckill.config.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 抢购结果回写与查询：Redis 优先，DB 兜底。
 * 对应 docs/01-技术设计.md §6.1 flash:result key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleResultService {

    public static final String STATUS_SUCCESS = "SUCCESS";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 发券成功后回写（TTL 24h，结果查询免查库） */
    public void writeSuccess(Long userId, Long activityId, UserCoupon coupon) {
        Map<String, Object> payload = Map.of(
                "status", STATUS_SUCCESS,
                "couponNo", coupon.getCouponNo(),
                "amount", coupon.getAmount() == null ? "0" : coupon.getAmount().toPlainString(),
                "minAmount", coupon.getMinAmount() == null ? "0" : coupon.getMinAmount().toPlainString(),
                "validStart", String.valueOf(coupon.getValidStart()),
                "validEnd", String.valueOf(coupon.getValidEnd()));
        try {
            redisTemplate.opsForValue().set(RedisKeys.result(userId, activityId),
                    objectMapper.writeValueAsString(payload), 24, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            log.warn("[result-write-fail] userId={} activityId={}", userId, activityId, e);
        }
    }

    /** 读取结果缓存，未命中返回 null */
    public String getCachedResult(Long userId, Long activityId) {
        return redisTemplate.opsForValue().get(RedisKeys.result(userId, activityId));
    }
}
