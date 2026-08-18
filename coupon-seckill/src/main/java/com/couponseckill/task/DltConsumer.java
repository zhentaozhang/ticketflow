package com.couponseckill.task;

import com.couponseckill.config.RedisKeys;
import com.couponseckill.kafka.FlashSaleRequestMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信消费（生产模式，mock-messaging=false 时装配）：
 * 重试耗尽的发券消息进入 DLT → 回补 Redis 库存（用户侧结果按失败处理）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon-seckill.mock-messaging", havingValue = "false", matchIfMissing = false)
public class DltConsumer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    @Qualifier("flashRollbackScript")
    private DefaultRedisScript<Long> rollbackScript;

    @KafkaListener(topics = "${spring.kafka.topic.flash-sale-dlt}", groupId = "coupon-seckill-dlt")
    public void onDlt(String json) {
        try {
            FlashSaleRequestMessage msg = objectMapper.readValue(json, FlashSaleRequestMessage.class);
            redisTemplate.execute(rollbackScript, List.of(
                    RedisKeys.stock(msg.getActivityId()),
                    RedisKeys.limit(msg.getActivityId(), msg.getUserId()),
                    RedisKeys.dedup(msg.getActivityId(), msg.getUserId(), msg.getRequestId())));
            log.warn("[dlt-rollback] activityId={} userId={} orderNo={}",
                    msg.getActivityId(), msg.getUserId(), msg.getOrderNo());
        } catch (Exception e) {
            log.error("[dlt-process-fail] json={}", json, e);
        }
    }
}
