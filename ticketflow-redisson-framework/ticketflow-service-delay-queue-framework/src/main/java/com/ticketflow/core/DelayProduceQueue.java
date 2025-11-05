package com.ticketflow.core;

import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * 延迟队列生产端。
 *
 * 包装 Redisson RDelayedQueue：
 *   offer(content, delayTime, unit) 将消息加入延迟队列，
 *   Redisson 内部会在到期后自动将元素转移到关联的 RBlockingQueue。
 *
 * 消费者端通过 RBlockingQueue.take() 阻塞获取已到期的元素。
 **/
public class DelayProduceQueue extends DelayBaseQueue{
    
    private final RDelayedQueue<String> delayedQueue;
    public DelayProduceQueue(RedissonClient redissonClient, final String relTopic) {
        super(redissonClient, relTopic);
        this.delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
    }
    
    public void offer(String content, long delayTime, TimeUnit timeUnit) {
        delayedQueue.offer(content,delayTime,timeUnit);
    }
}
