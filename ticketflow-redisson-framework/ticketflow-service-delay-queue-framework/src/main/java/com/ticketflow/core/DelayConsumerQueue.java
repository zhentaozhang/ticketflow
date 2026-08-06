package com.ticketflow.core;

import com.ticketflow.context.DelayQueuePart;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟队列消费端。
 *
 * 双线程池架构：
 *   listenStartThreadPool（1 线程）→ 阻塞 RBlockingQueue.take()，取出到期消息
 *                                     ↓ 提交到
 *   executeTaskThreadPool（可配置）→ 并行执行 ConsumerTask.execute()
 *
 * RBlockingQueue 的 take() 不能并发（单线程监听），因此消费端把消息取出后
 * 交给工作线程池处理，避免阻塞新到期的消息。
 *
 * listenStart() 使用 AtomicBoolean + synchronized 确保只启动一次。
 *
 * @see ConsumerTask 消费任务接口
 * @see DelayBaseQueue 底层的 RBlockingQueue
 **/
@Slf4j
public class DelayConsumerQueue extends DelayBaseQueue{
    
    private final AtomicInteger listenStartThreadCount = new AtomicInteger(1);
    
    private final AtomicInteger executeTaskThreadCount = new AtomicInteger(1);
    
    private final ThreadPoolExecutor listenStartThreadPool;
    
    private final ThreadPoolExecutor executeTaskThreadPool;
    
    private final AtomicBoolean runFlag = new AtomicBoolean(false);
    
    private final ConsumerTask consumerTask;
    
    public DelayConsumerQueue(DelayQueuePart delayQueuePart, String relTopic){
        super(delayQueuePart.getDelayQueueBasePart().getRedissonClient(),relTopic);
        this.listenStartThreadPool = new ThreadPoolExecutor(1,1,60, 
                TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r -> new Thread(Thread.currentThread().getThreadGroup(), r,
                "listen-start-thread-" + listenStartThreadCount.getAndIncrement()));
        this.executeTaskThreadPool = new ThreadPoolExecutor(
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getCorePoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getMaximumPoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getKeepAliveTime(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getUnit(),
                new LinkedBlockingQueue<>(delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getWorkQueueSize()),
                r -> new Thread(Thread.currentThread().getThreadGroup(), r, 
                        "delay-queue-consume-thread-" + executeTaskThreadCount.getAndIncrement()));
        this.consumerTask = delayQueuePart.getConsumerTask();
    }
    
    public synchronized void listenStart(){
        if (!runFlag.get()) {
            runFlag.set(true);
            listenStartThreadPool.execute(() -> {
                while (!Thread.interrupted()) {
                    try {
                        assert blockingQueue != null;
                        String content = blockingQueue.take();
                        executeTaskThreadPool.execute(() -> {
                            try {
                                consumerTask.execute(content);
                            }catch (Exception e) {
                                log.error("consumer execute error",e);
                            }
                        });
                    } catch (InterruptedException e) {
                        destroy(executeTaskThreadPool);
                    } catch (Throwable e) {
                        log.error("blockingQueue take error",e);
                    }
                }
            });
        }
    }
    
    public void destroy(ExecutorService executorService) {
        try {
            if (Objects.nonNull(executorService)) {
                executorService.shutdown();
            }
        } catch (Exception e) {
            log.error("destroy error",e);
        }
    }

    public void shutdown() {
        destroy(listenStartThreadPool);
        destroy(executeTaskThreadPool);
    }
}
