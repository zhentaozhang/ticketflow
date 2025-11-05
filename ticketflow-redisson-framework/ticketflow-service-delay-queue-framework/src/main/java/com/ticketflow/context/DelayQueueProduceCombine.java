package com.ticketflow.context;

import com.ticketflow.core.DelayProduceQueue;
import com.ticketflow.core.IsolationRegionSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 延迟队列生产者——分区路由。
 *
 * 将一个 topic 拆成 N 个物理分区（topic-0 ~ topic-N），
 * create N 个 DelayProduceQueue（每个包装一个独立的 Redisson RDelayedQueue）。
 *
 * 发送时通过 IsolationRegionSelector 轮询选择一个分区写入，
 * 将写入压力分散到 N 个 Redis 键上，缓解热点。
 *
 * 消费端（DelayQueueInitHandler）必须以相同的 partition count 和 topic 命名
 * 创建对应的 DelayConsumerQueue，否则消息会被留在队列中无法消费。
 **/
public class DelayQueueProduceCombine {
    
    private final IsolationRegionSelector isolationRegionSelector;
    
    private final List<DelayProduceQueue> delayProduceQueueList = new ArrayList<>();
    
    public DelayQueueProduceCombine(DelayQueueBasePart delayQueueBasePart,String topic){
        Integer isolationRegionCount = delayQueueBasePart.getDelayQueueProperties().getIsolationRegionCount();
        isolationRegionSelector =new IsolationRegionSelector(isolationRegionCount);
        //开始分片
        for(int i = 0; i < isolationRegionCount; i++) {
            delayProduceQueueList.add(new DelayProduceQueue(delayQueueBasePart.getRedissonClient(),topic + "-" + i));
        }
    }
    
    public void offer(String content,long delayTime, TimeUnit timeUnit){
        int index = isolationRegionSelector.getIndex();
        //拿取分片
        delayProduceQueueList.get(index).offer(content, delayTime, timeUnit);
    }
}
