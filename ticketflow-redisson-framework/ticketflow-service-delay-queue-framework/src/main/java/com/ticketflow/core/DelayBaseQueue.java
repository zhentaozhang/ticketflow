package com.ticketflow.core;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;

/**
 * 延迟队列的 Redis 阻塞队列基类。
 *
 * Redisson 的延迟队列（RDelayedQueue）底层依赖一个 RBlockingQueue：
 *   offer(element, delay, unit) → RDelayedQueue 到期后自动将元素转入关联的 RBlockingQueue
 *   take()                     → 消费者从 RBlockingQueue 阻塞获取已到期的元素
 *
 * 子类 DelayProduceQueue 持有 RDelayedQueue（写入端），
 * 子类 DelayConsumerQueue 持有 RBlockingQueue（消费端）。
 **/
@Slf4j
public class DelayBaseQueue {
    
    protected final RedissonClient redissonClient;
    protected final RBlockingQueue<String> blockingQueue;
    
    
    public DelayBaseQueue(RedissonClient redissonClient,String relTopic){
        this.redissonClient = redissonClient;
        this.blockingQueue = redissonClient.getBlockingQueue(relTopic);
    }
}
