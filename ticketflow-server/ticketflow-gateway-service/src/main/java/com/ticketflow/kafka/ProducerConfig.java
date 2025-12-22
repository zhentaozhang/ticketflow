package com.ticketflow.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka生产者配置。有条件的自动配置，当配置了 bootstrap-servers 时生效。
 */
@ConditionalOnProperty(value = "spring.kafka.bootstrap-servers")
public class ProducerConfig {

    @Bean
    public KafkaTopic kafkaTopic() {
        return new KafkaTopic();
    }

    @Bean
    public ApiDataMessageSend apiDataMessageSend(KafkaTemplate<String, String> kafkaTemplate, KafkaTopic kafkaTopic) {
        return new ApiDataMessageSend(kafkaTemplate, kafkaTopic.getTopic());
    }
}
