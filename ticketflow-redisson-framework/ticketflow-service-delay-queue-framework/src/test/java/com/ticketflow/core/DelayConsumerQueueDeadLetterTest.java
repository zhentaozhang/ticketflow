package com.ticketflow.core;

import com.ticketflow.config.DelayQueueProperties;
import com.ticketflow.context.DelayQueueBasePart;
import com.ticketflow.context.DelayQueuePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延迟队列死信处理测试。
 * <p>
 * 覆盖旧实现的三个漏洞：死信队列无 TTL（只能人工清理）、死信无读取接口（只写不读）、
 * 死信完全不可观测（没有指标/回调）。
 */
class DelayConsumerQueueDeadLetterTest {

    private static final String REL_TOPIC = "ticketflow-order-delay-cancel-0";

    private RedissonClient redissonClient;

    private RList<String> deadLetterQueue;

    private RAtomicLong retryCounter;

    private DelayQueueBasePart delayQueueBasePart;

    private DelayConsumerQueue delayConsumerQueue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        deadLetterQueue = mock(RList.class);
        retryCounter = mock(RAtomicLong.class);
        when(redissonClient.getBlockingQueue(anyString())).thenReturn(mock(RBlockingQueue.class));
        when(redissonClient.getDelayedQueue(any())).thenReturn(mock(RDelayedQueue.class));
        when(redissonClient.<String>getList(anyString())).thenReturn(deadLetterQueue);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(retryCounter);

        DelayQueueProperties properties = new DelayQueueProperties();
        properties.setIsolationRegionCount(1);
        properties.setMaxRetry(3);
        properties.setRetryBackoffSeconds(5L);
        delayQueueBasePart = new DelayQueueBasePart(redissonClient, properties);
        ConsumerTask consumerTask = mock(ConsumerTask.class);
        when(consumerTask.topic()).thenReturn(REL_TOPIC);
        delayConsumerQueue = new DelayConsumerQueue(new DelayQueuePart(delayQueueBasePart, consumerTask), REL_TOPIC);
    }

    @Test
    void 消息转入死信时写入队列并补有效期() {
        when(deadLetterQueue.size()).thenReturn(3);
        AtomicReference<String> notifiedTopic = new AtomicReference<>();
        AtomicReference<String> notifiedDeadLetterTopic = new AtomicReference<>();
        AtomicReference<Integer> notifiedSize = new AtomicReference<>();
        delayQueueBasePart.setDeadLetterListener((topic, deadLetterTopic, content, size, cause) -> {
            notifiedTopic.set(topic);
            notifiedDeadLetterTopic.set(deadLetterTopic);
            notifiedSize.set(size);
        });

        ReflectionTestUtils.invokeMethod(delayConsumerQueue, "moveToDeadLetter", "payload", new RuntimeException("boom"));

        verify(deadLetterQueue).add("payload");
        // 旧实现死信永不过期：只能靠人工 redis-cli 清理
        verify(deadLetterQueue).expire(7, TimeUnit.DAYS);
        // 死信写入后清理重试计数，避免计数 key 一直挂着
        verify(retryCounter).delete();
        assertEquals(REL_TOPIC, notifiedTopic.get());
        assertEquals(REL_TOPIC + ":dead-letter", notifiedDeadLetterTopic.get());
        assertEquals(3, notifiedSize.get());
    }

    @Test
    void 监听器抛异常不影响死信写入() {
        when(deadLetterQueue.size()).thenReturn(1);
        delayQueueBasePart.setDeadLetterListener((topic, deadLetterTopic, content, size, cause) -> {
            throw new IllegalStateException("指标注册失败");
        });

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(delayConsumerQueue, "moveToDeadLetter",
                "payload", new RuntimeException("boom")));

        verify(deadLetterQueue).add("payload");
    }

    @Test
    void 未注册监听器时使用空实现() {
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(delayConsumerQueue, "moveToDeadLetter",
                "payload", new RuntimeException("boom")));

        verify(deadLetterQueue).add("payload");
    }

    @Test
    void 可读取死信内容且上限非法时返回空() {
        when(deadLetterQueue.range(0, 1)).thenReturn(List.of("a", "b"));

        assertTrue(delayConsumerQueue.getDeadLetterMessages(2).containsAll(List.of("a", "b")));
        assertEquals(0, delayConsumerQueue.getDeadLetterMessages(0).size());
        assertTrue(delayConsumerQueue.getDeadLetterMessages(-1).isEmpty());
        verify(deadLetterQueue, never()).range(0, -2);
    }
}
