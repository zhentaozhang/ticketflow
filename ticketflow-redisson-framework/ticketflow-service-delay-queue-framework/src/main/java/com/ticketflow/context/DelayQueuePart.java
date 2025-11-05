package com.ticketflow.context;

import com.ticketflow.core.ConsumerTask;
import lombok.Data;

/**
 * 延迟队列消息主题——绑定一个 topic 和对应的 ConsumerTask。
 *
 * 由 DelayQueueInitHandler 在 ApplicationStartedEvent 时注册，
 * 每个 topic 可拆分多个分区（topic-0 ~ topic-N）并行消费
 */
@Data
public class DelayQueuePart {
    
    private final DelayQueueBasePart delayQueueBasePart;
 
    private final ConsumerTask consumerTask;
    
    public DelayQueuePart(DelayQueueBasePart delayQueueBasePart, ConsumerTask consumerTask){
        this.delayQueueBasePart = delayQueueBasePart;
        this.consumerTask = consumerTask;
    }
}
