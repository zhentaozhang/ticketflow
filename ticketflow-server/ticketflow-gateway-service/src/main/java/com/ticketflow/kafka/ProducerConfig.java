package com.ticketflow.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
    public ApiDataMessageSend apiDataMessageSend(KafkaTemplate<String, String> kafkaTemplate,
                                                 KafkaTopic kafkaTopic,
                                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 指标注册表可能没有（未引入 actuator/micrometer 时会 empty），生产者自己容错
        return new ApiDataMessageSend(kafkaTemplate, kafkaTopic.getTopic(), meterRegistryProvider.getIfAvailable());
    }
}
