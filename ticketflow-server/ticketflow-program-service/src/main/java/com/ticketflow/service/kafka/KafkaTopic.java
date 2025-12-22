package com.ticketflow.service.kafka;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kafka topic 配置，从 application.yml 的 spring.kafka.topic 读取。
 * V4 订单创建模式的异步消息通道——ProgramOrderV4Strategy 将订单消息
 * 发送到此 topic，由 Kafka 消费者端异步处理订单持久化。
 */
@Data
@Component
public class KafkaTopic {
    
    @Value("${spring.kafka.topic:default}")
    private String topic;

}
