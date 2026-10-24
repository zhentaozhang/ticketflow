package com.ticketflow.event;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.context.DelayQueueBasePart;
import com.ticketflow.context.DelayQueuePart;
import com.ticketflow.core.ConsumerTask;
import com.ticketflow.core.DelayConsumerQueue;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 延迟队列初始化——监听 ApplicationStartedEvent，在 Spring 容器就绪后启动所有 topic 的消费者。
 *
 * 从 Spring 容器中获取所有 DelayQueuePart Bean，
 * 为每个 topic 创建 DelayConsumerQueue，启动独立线程池消费到期消息。
 * 应用关闭时通过 @PreDestroy 优雅停止所有消费者线程池。
 */
public class DelayQueueInitHandler implements ApplicationListener<ApplicationStartedEvent> {
    
    private final DelayQueueBasePart delayQueueBasePart;
    
    private final List<DelayConsumerQueue> delayConsumerQueueList = new ArrayList<>();
    
    public DelayQueueInitHandler(DelayQueueBasePart delayQueueBasePart){
        this.delayQueueBasePart = delayQueueBasePart;
    }
    
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {

        Map<String, ConsumerTask> consumerTaskMap = event.getApplicationContext().getBeansOfType(ConsumerTask.class);
        if (CollectionUtil.isEmpty(consumerTaskMap)) {
            return;
        }
        for (ConsumerTask consumerTask : consumerTaskMap.values()) {
            DelayQueuePart delayQueuePart = new DelayQueuePart(delayQueueBasePart,consumerTask);
            Integer isolationRegionCount = delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties()
                    .getIsolationRegionCount();
            
            for(int i = 0; i < isolationRegionCount; i++) {
                DelayConsumerQueue delayConsumerQueue = new DelayConsumerQueue(delayQueuePart, 
                        delayQueuePart.getConsumerTask().topic() + "-" + i);
                delayConsumerQueueList.add(delayConsumerQueue);
                delayConsumerQueue.listenStart();
            }
        }
    }

    /**
     * 读取所有分区队列的死信消息（供运维巡检 / 人工重放）。
     *
     * 死信状态被切成 isolationRegionCount 份（每个分区一个 key），因此这里按
     * 队列逐个读并合并：只有死信内容的 key 才会出现在返回的 Map 里。
     *
     * @param limitPerTopic 每个分区队列最多读取的条数
     * @return key = 队列 topic（含分区后缀），value = 消息原文列表
     */
    public Map<String, List<String>> getDeadLetters(int limitPerTopic) {
        Map<String, List<String>> deadLetters = new LinkedHashMap<>();
        for (DelayConsumerQueue delayConsumerQueue : delayConsumerQueueList) {
            List<String> messages = delayConsumerQueue.getDeadLetterMessages(limitPerTopic);
            if (CollectionUtil.isNotEmpty(messages)) {
                deadLetters.put(delayConsumerQueue.getRelTopic(), messages);
            }
        }
        return deadLetters;
    }

    /** 各分区队列的死信积压条数，供指标采集与巡检。 */
    public Map<String, Integer> getDeadLetterSizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (DelayConsumerQueue delayConsumerQueue : delayConsumerQueueList) {
            sizes.put(delayConsumerQueue.getRelTopic(), delayConsumerQueue.getDeadLetterQueueSize());
        }
        return sizes;
    }

    @PreDestroy
    public void shutdown() {
        delayConsumerQueueList.forEach(DelayConsumerQueue::shutdown);
    }
}
