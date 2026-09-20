package com.ticketflow.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.entity.ApiData;
import com.ticketflow.service.ApiDataService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * API数据消息消费者。从Kafka消费API调用记录，异步写入数据库。
 */
@Slf4j
@AllArgsConstructor
@Component
public class ApiDataMessageConsumer {

    @Autowired
    private ApiDataService apiDataService;

    @KafkaListener(topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "${spring.kafka.topic:save_api_data}"})
    public void consumerOrderMessage(ConsumerRecord<String, String> consumerRecord) {
        // 不再吞异常：offset 已关闭自动提交（listener.ack-mode=record），
        // 处理失败必须抛出，让容器 error handler 重试；吞掉会导致 offset 照常提交、审计数据静默丢失。
        Optional.ofNullable(consumerRecord.value()).map(String::valueOf).ifPresent(value -> {
            log.info("consumerOrderMessage message:{}", value);
            ApiData apiData = JSON.parseObject(value, ApiData.class);
            apiDataService.saveApiData(apiData);
        });
    }
}
