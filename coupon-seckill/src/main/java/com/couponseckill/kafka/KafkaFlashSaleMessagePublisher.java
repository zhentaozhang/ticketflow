package com.couponseckill.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Kafka 发券消息实现（生产模式）。
 * 幂等 Producer + acks=all（application.yml），key=userId 保证同用户有序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon-seckill.mock-messaging", havingValue = "false", matchIfMissing = false)
public class KafkaFlashSaleMessagePublisher implements FlashSaleMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.topic.flash-sale-request}")
    private String topic;

    @Override
    public void publish(FlashSaleRequestMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize message failed", e);
        }
        try {
            kafkaTemplate.send(topic, String.valueOf(message.getUserId()), json).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // 发送失败（不可达/超时），由调用方回补 Redis 库存
            log.error("[kafka-send-fail] activityId={} userId={} orderNo={}", message.getActivityId(),
                    message.getUserId(), message.getOrderNo(), e);
            throw new IllegalStateException("kafka send failed", e);
        }
    }
}
