package com.ticketflow.kafka;

import com.ticketflow.core.SpringUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * API 限流触发的告警消息 Kafka 生产者。
 * 当 ApiRestrictService 检测到限流规则首次触发时（triggerCallStat=1/2），
 * 通过此生产者发送告警消息到 Kafka，供监控系统消费。
 * <p>
 * 环境隔离：topic 前自动拼接 SpringUtil.getPrefixDistinctionName()
 * <p>
 * 失败可见性：旧实现直接丢弃 send(...) 返回的 future，Kafka 不可用、序列化失败、
 * 异步超时都只剩框架自己的一行 WARN，限流告警静默消失。现在挂 whenComplete 回调，
 * 失败打可检索的错误日志并计入 {@code ticketflow_api_data_alert_send_total{result=fail}}。
 */
@Slf4j
@AllArgsConstructor
public class ApiDataMessageSend {

    /** 指标名：限流告警消息投递结果，tags：result=success|fail */
    private static final String ALERT_SEND_TOTAL = "ticketflow_api_data_alert_send_total";

    private KafkaTemplate<String, String> kafkaTemplate;

    private String topic;

    private MeterRegistry meterRegistry;

    public void sendMessage(String message) {
        String realTopic = SpringUtil.getPrefixDistinctionName() + "-" + topic;
        log.info("sendMessage topic : {} message : {}", realTopic, message);
        try {
            kafkaTemplate.send(realTopic, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("限流告警消息投递失败，本次告警丢失 topic : {} message : {}", realTopic, message, ex);
                    count("fail");
                    return;
                }
                count("success");
            });
        } catch (Exception e) {
            // 同步异常（模板未就绪/参数非法）不会走到 whenComplete
            log.error("限流告警消息投递异常 topic : {} message : {}", realTopic, message, e);
            count("fail");
        }
    }

    private void count(String result) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(ALERT_SEND_TOTAL, "result", result).increment();
            }
        } catch (Exception e) {
            log.warn("限流告警投递指标记录失败 result : {}", result, e);
        }
    }
}
