package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.exception.TicketFlowFrameException;
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
 * V4 策略使用的消息通道——ProgramOrderV4Strategy 调用 createNewAsync()
 * 发送到此 Kafka topic，由消费端异步处理订单持久化。
 * <p>
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
     * 异步发送订单创建消息到 Kafka（指定 messageKey 进行分区路由）。
     *
     * @param key             消息 Key（如订单号，保证同一订单路由到同一分区有序处理）
     * @param message         JSON 格式的订单消息体
     * @param successCallback 发送成功回调（记录发送日志等）
     * @param failureCallback 发送失败回调（记录异常和重试）
     */
    public void sendMessage(String key, String message, SuccessCallback<SendResult<String, String>> successCallback,
                            FailureCallback failureCallback) {
        if (log.isDebugEnabled()) {
            log.debug("创建订单kafka发送消息 key : {} 消息体 : {}", key, message);
        }
        try {
            CompletableFuture<SendResult<String, String>> completableFuture =
                    kafkaTemplate.send(SpringUtil.getPrefixDistinctionName() + "-" + kafkaTopic.getTopic(), key, message);
            if (completableFuture == null) {
                failureCallback.onFailure(new TicketFlowFrameException("KafkaTemplate send returned null future"));
                return;
            }
            completableFuture.whenComplete((result, ex) -> {
                if (Objects.isNull(ex)) {
                    successCallback.onSuccess(result);
                } else {
                    failureCallback.onFailure(ex);
                }
            });
        } catch (Throwable t) {
            failureCallback.onFailure(t);
        }
    }

    /**
     * 异步发送订单创建消息到 Kafka。
     * 自动从消息体中提取 orderNumber 作为 Kafka Message Key，若无则使用默认发送。
     *
     * @param message         JSON 格式的订单消息体
     * @param successCallback 发送成功回调（记录发送日志等）
     * @param failureCallback 发送失败回调（记录异常和重试）
     */
    public void sendMessage(String message, SuccessCallback<SendResult<String, String>> successCallback,
                            FailureCallback failureCallback) {
        String key = null;
        try {
            JSONObject jsonObject = JSON.parseObject(message);
            if (jsonObject != null && jsonObject.containsKey("orderNumber")) {
                key = jsonObject.getString("orderNumber");
            }
        } catch (Exception ignored) {
        }
        if (key != null) {
            sendMessage(key, message, successCallback, failureCallback);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("创建订单kafka发送消息 消息体 : {}", message);
            }
            try {
                CompletableFuture<SendResult<String, String>> completableFuture =
                        kafkaTemplate.send(SpringUtil.getPrefixDistinctionName() + "-" + kafkaTopic.getTopic(), message);
                if (completableFuture == null) {
                    failureCallback.onFailure(new TicketFlowFrameException("KafkaTemplate send returned null future"));
                    return;
                }
                completableFuture.whenComplete((result, ex) -> {
                    if (Objects.isNull(ex)) {
                        successCallback.onSuccess(result);
                    } else {
                        failureCallback.onFailure(ex);
                    }
                });
            } catch (Throwable t) {
                failureCallback.onFailure(t);
            }
        }
    }
}
