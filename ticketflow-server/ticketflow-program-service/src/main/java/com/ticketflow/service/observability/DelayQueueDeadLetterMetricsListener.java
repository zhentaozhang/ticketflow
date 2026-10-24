package com.ticketflow.service.observability;

import com.ticketflow.core.DeadLetterListener;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 延迟队列死信指标（program-service侧）。
 *
 * 延迟队列框架模块不依赖 micrometer，因此框架只在消息转入死信时回调
 * {@link DeadLetterListener}，由本类把「死信发生」变成可监控的数字。
 *
 * 指标 {@link Metrics#DELAY_QUEUE_DEAD_LETTER_TOTAL}{@code {topic=...}} 一旦非零，
 * 说明有消息重试到上限（默认 3 次）仍消费失败：需要按日志里的 deadLetterTopic
 * 读取死信内容（{@code DelayConsumerQueue#getDeadLetterMessages}）并人工重放。
 */
@Slf4j
@Component
public class DelayQueueDeadLetterMetricsListener implements DeadLetterListener {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Override
    public void onDeadLetter(String topic, String deadLetterTopic, String content, int size, Throwable cause) {
        BusinessMetrics.increment(meterRegistry, Metrics.DELAY_QUEUE_DEAD_LETTER_TOTAL, Metrics.TAG_TOPIC, topic);
        log.error("延迟队列消息转入死信，需人工核对或重放 deadLetterTopic : {} deadLetterQueueSize : {} content : {}",
                deadLetterTopic, size, content, cause);
    }
}
