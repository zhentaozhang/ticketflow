package com.ticketflow.context;

import com.ticketflow.config.DelayQueueProperties;
import com.ticketflow.core.DeadLetterListener;
import lombok.Getter;
import lombok.Setter;
import org.redisson.api.RedissonClient;

/**
 * 延迟队列基础配置——封装 RedissonClient + 分区数等全局参数。
 *
 * 由 DelayQueueContext 在初始化时创建，传递给每个 DelayQueuePart。
 *
 * deadLetterListener 不是构造参数：框架模块不依赖 micrometer，由业务服务在启动时
 * 通过 setDeadLetterListener 注入观测实现（打点/告警），默认空实现。
 */
@Getter
public class DelayQueueBasePart {

    private final RedissonClient redissonClient;

    private final DelayQueueProperties delayQueueProperties;

    /** 死信观测回调，默认空实现；由业务服务的 DeadLetterListener 实现覆盖。 */
    @Setter
    private volatile DeadLetterListener deadLetterListener = DeadLetterListener.NOOP;

    public DelayQueueBasePart(RedissonClient redissonClient, DelayQueueProperties delayQueueProperties) {
        this.redissonClient = redissonClient;
        this.delayQueueProperties = delayQueueProperties;
    }
}
