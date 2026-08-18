package com.couponseckill.service;

import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.RedisKeys;
import com.couponseckill.dto.GrabRequest;
import com.couponseckill.dto.GrabResult;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.kafka.FlashSaleMessagePublisher;
import com.couponseckill.kafka.FlashSaleRequestMessage;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 抢购服务：同步路径只碰 Redis（单 Lua 原子），成功后异步发券。
 * 对应 docs/01-技术设计.md §4.3 与 §6.2。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleGrabService {

    private final StringRedisTemplate redisTemplate;
    private final FlashSaleMessagePublisher messagePublisher;
    private final FlashSaleActivityMapper activityMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ActivityMetaCache metaCache;

    // 注意：@Qualifier 不会随 Lombok 构造器复制，故两个脚本用字段注入按名称解析
    @Autowired
    @Qualifier("flashGrabScript")
    private DefaultRedisScript<Long> grabScript;

    @Autowired
    @Qualifier("flashRollbackScript")
    private DefaultRedisScript<Long> rollbackScript;

    @Value("${coupon-seckill.grab.dedup-ttl-seconds:60}")
    private int dedupTtlSeconds;

    /**
     * 抢购入口。
     *
     * @param userId 登录用户（集成阶段由网关/鉴权填充）
     */
    public GrabResult grab(Long userId, GrabRequest req) {
        Long activityId = req.getActivityId();
        long now = System.currentTimeMillis();

        // 0) 快速失败（本地缓存，权威校验在 Lua）
        preCheck(activityId, now);

        // 1) Lua 原子：时间窗 + 幂等 + 限购 + 扣库存
        List<String> keys = List.of(
                RedisKeys.stock(activityId),
                RedisKeys.limit(activityId, userId),
                RedisKeys.meta(activityId),
                RedisKeys.dedup(activityId, userId, req.getRequestId()));
        Long result;
        try {
            result = redisTemplate.execute(grabScript, keys, String.valueOf(now), String.valueOf(dedupTtlSeconds));
        } catch (Exception e) {
            // Redis 不可用：返回系统繁忙，绝不落库放行
            log.error("[grab-redis-error] activityId={} userId={}", activityId, userId, e);
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }

        int code = result == null ? Integer.MIN_VALUE : result.intValue();
        switch (code) {
            case 1 -> {
                return issueAsync(userId, req, now);
            }
            case -1 -> throw new BizException(ErrorCode.STOCK_EMPTY);
            case -2 -> throw new BizException(ErrorCode.OVER_LIMIT);
            case -4 -> throw new BizException(ErrorCode.DUPLICATE_REQUEST);
            case -3 -> throw mapNotActive(activityId, now);
            default -> throw new BizException(ErrorCode.SYSTEM_BUSY);
        }
    }

    /**
     * 扣减成功：生成流水号并异步投递发券消息。
     * Kafka 发送失败 → 立即反向回补库存/限购/幂等标记，返回系统繁忙。
     */
    private GrabResult issueAsync(Long userId, GrabRequest req, long now) {
        String orderNo = "FS" + idGenerator.nextIdStr();
        FlashSaleRequestMessage message = FlashSaleRequestMessage.of(
                req.getActivityId(), userId, req.getRequestId(), orderNo, now);
        try {
            messagePublisher.publish(message);
        } catch (Exception e) {
            // 发送失败 → 回补（库存/限购/幂等标记），保证"扣了库存但没发出消息"的窗口尽可能短
            log.warn("[grab-rollback] activityId={} userId={} orderNo={}", req.getActivityId(), userId, orderNo, e);
            rollback(req.getActivityId(), userId, req.getRequestId());
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }
        return GrabResult.processing(orderNo);
    }

    /** 反向回补 Redis（rollback.lua） */
    private void rollback(Long activityId, Long userId, String requestId) {
        try {
            redisTemplate.execute(rollbackScript, List.of(
                    RedisKeys.stock(activityId),
                    RedisKeys.limit(activityId, userId),
                    RedisKeys.dedup(activityId, userId, requestId)));
        } catch (Exception e) {
            // 回补失败：交给对账任务收敛
            log.error("[grab-rollback-fail] activityId={} userId={}", activityId, userId, e);
        }
    }

    private void preCheck(Long activityId, long now) {
        ActivityMetaCache.ActivityMeta meta = metaCache.get(activityId);
        if (meta == null) {
            FlashSaleActivity activity = activityMapper.selectById(activityId);
            if (activity == null) {
                return; // Lua 兜底
            }
            meta = new ActivityMetaCache.ActivityMeta(activity.getStatus(),
                    activity.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    activity.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            metaCache.put(activityId, meta);
        }
        if (meta.getStatus() == FlashSaleActivity.STATUS_ENDED || now > meta.getEndTs()) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        if (meta.getStatus() == FlashSaleActivity.STATUS_OFFLINE) {
            throw new BizException(ErrorCode.ACTIVITY_OFFLINE);
        }
        if (meta.getStatus() == FlashSaleActivity.STATUS_NOT_STARTED && now < meta.getStartTs()) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (meta.getStatus() == FlashSaleActivity.STATUS_DRAFT) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
    }

    /** Lua 返回 -3（时间窗外/meta 缺失）时，查 DB 区分具体原因 */
    private BizException mapNotActive(Long activityId, long now) {
        FlashSaleActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStatus() == FlashSaleActivity.STATUS_OFFLINE) {
            return new BizException(ErrorCode.ACTIVITY_OFFLINE);
        }
        long startTs = activity.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = activity.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (now < startTs || activity.getStatus() == FlashSaleActivity.STATUS_NOT_STARTED
                || activity.getStatus() == FlashSaleActivity.STATUS_DRAFT) {
            return new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (now > endTs || activity.getStatus() == FlashSaleActivity.STATUS_ENDED) {
            return new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        return new BizException(ErrorCode.ACTIVITY_OFFLINE);
    }
}
