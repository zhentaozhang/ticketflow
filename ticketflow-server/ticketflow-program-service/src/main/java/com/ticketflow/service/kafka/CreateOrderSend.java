package com.ticketflow.service.kafka;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.mq.callback.FailureCallback;
import com.ticketflow.mq.callback.SuccessCallback;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 异步订单创建生产者。
 * V4/V41 策略使用的消息通道——ProgramOrderV41Strategy 调用 createNewAsync()
 * 发送到此 Kafka topic，由消费端异步处理订单持久化。
 *
 * 支持异步回调（SuccessCallback / FailureCallback）用于记录消息发送日志
 */
@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderSend {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private KafkaTopic kafkaTopic;
    
    
    /**
     * 异步发送订单创建消息到 Kafka。
     * topic 名 = 环境前缀 + kafkaTopic 配置值，通过 CompletableFuture 回调通知发送结果。
     *
     * @param message          JSON 格式的订单消息体
     * @param successCallback  发送成功回调（记录发送日志等）
     * @param failureCallback  发送失败回调（记录异常和重试）
     */
    public void sendMessage(String message, SuccessCallback<SendResult<String, String>> successCallback, 
                            FailureCallback failureCallback) {
        log.info("创建订单kafka发送消息 消息体 : {}", message);
        CompletableFuture<SendResult<String, String>> completableFuture = 
                kafkaTemplate.send(SpringUtil.getPrefixDistinctionName() + "-" + kafkaTopic.getTopic(), message);
        completableFuture.whenComplete((result,ex) -> {
            if (Objects.isNull(ex)) {
                successCallback.onSuccess(result);
            }else {
                failureCallback.onFailure(ex);
            }
        });
    }
}
