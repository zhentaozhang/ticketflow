package com.ticketflow.kafka;

import com.ticketflow.core.SpringUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * API 限流触发的告警消息 Kafka 生产者。
 * 当 ApiRestrictService 检测到限流规则首次触发时（triggerCallStat=1/2），
 * 通过此生产者发送告警消息到 Kafka，供监控系统消费。
 * <p>
 * 环境隔离：topic 前自动拼接 SpringUtil.getPrefixDistinctionName()
 */
@Slf4j
@AllArgsConstructor
public class ApiDataMessageSend {

    private KafkaTemplate<String, String> kafkaTemplate;

    private String topic;

    public void sendMessage(String message) {
        log.info("sendMessage message : {}", message);
        kafkaTemplate.send(SpringUtil.getPrefixDistinctionName() + "-" + topic, message);
    }
}
