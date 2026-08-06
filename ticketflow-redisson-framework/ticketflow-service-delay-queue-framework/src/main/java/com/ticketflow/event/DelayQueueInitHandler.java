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

    @PreDestroy
    public void shutdown() {
        delayConsumerQueueList.forEach(DelayConsumerQueue::shutdown);
    }
}
