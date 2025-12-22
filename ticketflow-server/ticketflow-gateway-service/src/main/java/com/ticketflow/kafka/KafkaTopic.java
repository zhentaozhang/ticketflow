package com.ticketflow.kafka;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;

/**
 * Kafka主题配置。从环境配置读取topic名称，用于网关层消息发送。
 */
@Data
public class KafkaTopic {
    
    @Value("${spring.kafka.topic:default}")
    private String topic;

}
