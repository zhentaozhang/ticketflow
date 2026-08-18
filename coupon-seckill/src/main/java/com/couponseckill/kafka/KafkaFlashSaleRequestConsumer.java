package com.couponseckill.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 发券消费者（生产模式，mock-messaging=false 时装配）。
 * 手动提交：业务落库成功后才 ack；失败抛异常交给重试（配置 max.poll 与重试，超限进 DLT 见 ReconcileTask/DltTask）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon-seckill.mock-messaging", havingValue = "false", matchIfMissing = false)
public class KafkaFlashSaleRequestConsumer {

    private final FlashSaleIssueHandler handler;

    @KafkaListener(topics = "${spring.kafka.topic.flash-sale-request}", concurrency = "8",
            groupId = "coupon-seckill")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            handler.handle(record.value());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[kafka-consume-fail] offset={} key={} value={}",
                    record.offset(), record.key(), record.value(), e);
            throw e;
        }
    }
}
