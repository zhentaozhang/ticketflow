package com.ticketflow.core;

/**
 * 死信监听——延迟队列把消息移入死信队列时回调外部实现。
 *
 * 框架模块 ticketflow-service-delay-queue-framework 刻意不引入 micrometer，
 * 因此观测接入通过本接口反转：业务服务注册一个带 MeterRegistry 的实现即可打点/告警。
 * 不注册时使用 {@link #NOOP}，行为与改造前完全一致。
 */
@FunctionalInterface
public interface DeadLetterListener {

    /** 不做任何处理的默认实现（未注册监听器时使用）。 */
    DeadLetterListener NOOP = (topic, deadLetterTopic, content, size, cause) -> { };

    /**
     * 消息已转入死信队列。
     *
     * @param topic 发生死信的主题（含分区后缀，如 xxx-delay-order-cancel-3）
     * @param deadLetterTopic 死信队列 key（topic + ":dead-letter"）
     * @param content 消息原文
     * @param size 写入后该死信队列长度（写入失败时为 0）
     * @param cause 触发死信的异常
     */
    void onDeadLetter(String topic, String deadLetterTopic, String content, int size, Throwable cause);
}
