package com.ticketflow.core;

import com.ticketflow.context.DelayQueuePart;
import com.ticketflow.trace.TraceContextHandle;
import com.ticketflow.trace.TraceContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RList;

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
 * 可靠性：RBlockingQueue.take() 出队即代表消息「物理删除」，业务执行抛异常时
 * 消息不会自动回到队列。因此消费失败时按递增退避重投回延迟队列（最多 maxRetry 次），
 * 超过重试上限后转入死信队列（topic + ":dead-letter"）并告警，避免订单超时取消等任务被静默丢弃。
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

    /**
     * 监听循环运行标志。shutdown() 置 false 并 shutdownNow 打断 take()，让监听线程退出。
     * 旧实现用 {@code while(!Thread.interrupted())}：捕获 InterruptedException 后未 break、
     * 也未恢复中断标记，循环会再次阻塞在 take()，线程永远退不出。
     */
    private volatile boolean running = true;

    private final ConsumerTask consumerTask;

    /** 主题名（含分区后缀），用于构造重试计数与死信队列的 key。 */
    private final String relTopic;

    /** 消息重投用的延迟队列（与生产端共享同一 Redisson 延迟队列）。 */
    private final RDelayedQueue<String> retryDelayedQueue;

    /** 死信队列：超过重试上限的消息转入此处，供人工/告警处理。 */
    private final RList<String> deadLetterQueue;

    /** 消费失败最大重试次数。 */
    private final int maxRetry;

    /** 重试基础退避时间（秒）。 */
    private final long retryBackoffSeconds;

    public DelayConsumerQueue(DelayQueuePart delayQueuePart, String relTopic){
        super(delayQueuePart.getDelayQueueBasePart().getRedissonClient(),relTopic);
        this.relTopic = relTopic;
        this.retryDelayedQueue = delayQueuePart.getDelayQueueBasePart().getRedissonClient().getDelayedQueue(blockingQueue);
        this.deadLetterQueue = delayQueuePart.getDelayQueueBasePart().getRedissonClient().getList(relTopic + ":dead-letter");
        this.maxRetry = delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getMaxRetry();
        this.retryBackoffSeconds = delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getRetryBackoffSeconds();
        this.listenStartThreadPool = new ThreadPoolExecutor(1,1,60, 
                TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r -> {
                    Thread t = new Thread(Thread.currentThread().getThreadGroup(), r,
                            "listen-start-thread-" + listenStartThreadCount.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        this.executeTaskThreadPool = new ThreadPoolExecutor(
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getCorePoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getMaximumPoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getKeepAliveTime(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getUnit(),
                new LinkedBlockingQueue<>(delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getWorkQueueSize()),
                r -> {
                    Thread t = new Thread(Thread.currentThread().getThreadGroup(), r,
                            "delay-queue-consume-thread-" + executeTaskThreadCount.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                // 有界队列 + 默认 AbortPolicy 会在饱和时抛 RejectedExecutionException，
                // 而消息此时已从 Redis 取出（take 已消费），异常被外层 catch(Throwable) 吞掉 → 消息永久丢失。
                // CallerRunsPolicy 让监听线程亲自执行，形成背压、绝不丢消息。
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.consumerTask = delayQueuePart.getConsumerTask();
    }
    
    public synchronized void listenStart(){
        if (!runFlag.get()) {
            runFlag.set(true);
            listenStartThreadPool.execute(() -> {
                while (running) {
                    try {
                        assert blockingQueue != null;
                        String content = blockingQueue.take();
                        executeTaskThreadPool.execute(() -> {
                            TraceContextUtil.UnwrapResult unwrapResult = TraceContextUtil.unwrap(content);
                            try (TraceContextHandle handle = TraceContextUtil.openScope(unwrapResult.getHeaders())) {
                                try {
                                    consumerTask.execute(unwrapResult.getPayload());
                                    // 执行成功：清理该消息的重试计数
                                    clearRetryCount(content);
                                } catch (Exception e) {
                                    handleConsumeFailure(content, e);
                                }
                            }
                        });
                    } catch (InterruptedException e) {
                        // 关停信号：恢复中断标记并退出循环（旧实现没有 break，线程会一直阻塞在 take）
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable e) {
                        log.error("blockingQueue take error",e);
                    }
                }
            });
        }
    }

    /**
     * 消费失败处理：按递增退避重投回延迟队列，超过上限后转入死信队列。
     * 业务消费者需保证幂等（重投可能造成重复执行）。
     */
    private void handleConsumeFailure(String content, Exception e) {
        int attempt = (int) incrementRetryCount(content);
        if (attempt <= maxRetry) {
            long delay = attempt * retryBackoffSeconds;
            try {
                retryDelayedQueue.offer(content, delay, TimeUnit.SECONDS);
                log.warn("延迟队列消费失败，将于 {}s 后第 {} 次重试 topic : {}", delay, attempt, relTopic, e);
            } catch (Exception rethrowException) {
                log.error("延迟队列消费失败消息重投失败，转入死信 topic : {}", relTopic, rethrowException);
                moveToDeadLetter(content, e);
            }
        } else {
            log.error("延迟队列消费失败已达重试上限 {}，转入死信 topic : {}", maxRetry, relTopic, e);
            moveToDeadLetter(content, e);
        }
    }

    private void moveToDeadLetter(String content, Exception e) {
        try {
            deadLetterQueue.add(content);
            log.error("[DEAD_LETTER_EVENT] 消息已转入死信队列 topic : {} deadLetterQueueSize : {} content : {}",
                    relTopic, deadLetterQueue.size(), content, e);
        } catch (Exception deadLetterException) {
            log.error("[DEAD_LETTER_ALERT] 写入死信队列失败，消息丢失需紧急人工处理 topic : {} content : {}",
                    relTopic, content, deadLetterException);
        } finally {
            clearRetryCount(content);
        }
    }

    private RAtomicLong retryCounter(String content) {
        // 使用 long 强转防 Integer.MIN_VALUE 取绝对值溢出为负数
        long safeHash = Math.abs((long) Objects.hashCode(content));
        return redissonClient.getAtomicLong(relTopic + ":retry:" + safeHash);
    }

    private long incrementRetryCount(String content) {
        RAtomicLong counter = retryCounter(content);
        long count = counter.incrementAndGet();
        // 计数保留 1 小时，避免长期堆积；正常消费成功会立即删除
        counter.expire(1, TimeUnit.HOURS);
        return count;
    }

    private void clearRetryCount(String content) {
        try {
            retryCounter(content).delete();
        } catch (Exception e) {
            log.warn("清理延迟队列重试计数失败 topic : {}", relTopic, e);
        }
    }

    /**
     * 获取死信队列当前积压消息数（供监控指标采集与运维巡检）。
     */
    public int getDeadLetterQueueSize() {
        return deadLetterQueue != null ? deadLetterQueue.size() : 0;
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
        running = false;
        // shutdownNow 会中断阻塞在 take() 的监听线程，配合 running=false 使其退出
        destroy(listenStartThreadPool);
        try {
            listenStartThreadPool.shutdownNow();
        } catch (Exception e) {
            log.error("destroy listenStartThreadPool error", e);
        }
        destroy(executeTaskThreadPool);
    }
}
