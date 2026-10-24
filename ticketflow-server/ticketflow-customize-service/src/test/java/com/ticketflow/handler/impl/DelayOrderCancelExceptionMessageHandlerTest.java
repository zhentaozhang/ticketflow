package com.ticketflow.handler.impl;

import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.observability.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息对账重投的上限与退避。
 *
 * 旧实现每次重投都把对账状态重置回「未对账」、不记次数、不写终态，
 * 一条永远消费不了的消息会被每分钟无限重投。这里锁住三件事：
 * 重投次数递增、退避时间下排、超过上限转终态且不再投递。
 */
@ExtendWith(MockitoExtension.class)
class DelayOrderCancelExceptionMessageHandlerTest {

    @Mock
    private MessageProducerRecordMapper messageProducerRecordMapper;

    @Mock
    private DelayQueueContext delayQueueContext;

    private SimpleMeterRegistry meterRegistry;

    private DelayOrderCancelExceptionMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DelayOrderCancelExceptionMessageHandler();
        ReflectionTestUtils.setField(handler, "messageProducerRecordMapper", messageProducerRecordMapper);
        ReflectionTestUtils.setField(handler, "delayQueueContext", delayQueueContext);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(handler, "meterRegistry", meterRegistry);
    }

    private MessageProducerRecord record(Integer retryCount) {
        MessageProducerRecord record = new MessageProducerRecord();
        record.setId(1001L);
        record.setMessageId(2001L);
        record.setMessageTopic("ticketflow-delay-order-cancel");
        record.setMessageContent("{\"orderNumber\":1001}");
        record.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        record.setRetryCount(retryCount);
        return record;
    }

    private MessageProducerRecord captureUpdate() {
        ArgumentCaptor<MessageProducerRecord> captor = ArgumentCaptor.forClass(MessageProducerRecord.class);
        verify(messageProducerRecordMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private double counter(String result) {
        try {
            return meterRegistry.get(Metrics.MESSAGE_RECONCILE_TOTAL).tag(Metrics.RESULT, result).counter().count();
        } catch (Exception e) {
            return 0d;
        }
    }

    @Test
    void 首次重投记一次并排下次时间() {
        when(messageProducerRecordMapper.updateById(any(MessageProducerRecord.class))).thenReturn(1);

        Date before = new Date();
        assertTrue(handler.handle(record(null)));

        verify(delayQueueContext).sendMessage(anyString(), anyString(), eq(1L), eq(TimeUnit.SECONDS));
        MessageProducerRecord updated = captureUpdate();
        assertEquals(1, updated.getRetryCount());
        assertEquals(MessageSendStatus.SEND_SUCCESS.getCode(), updated.getMessageSendStatus());
        assertEquals(ReconciliationStatus.RECONCILIATION_NO.getCode(), updated.getReconciliationStatus());
        assertNotNull(updated.getNextRetryTime());
        // 第 1 次退避 60 秒
        long gapSeconds = (updated.getNextRetryTime().getTime() - before.getTime()) / 1000L;
        assertTrue(gapSeconds >= 59 && gapSeconds <= 65, "退避时间应约为 60 秒，实际 " + gapSeconds);
        assertEquals(1d, counter(Metrics.RESULT_SUCCESS));
    }

    @Test
    void 第五次仍在重投范围内并按指数退避() {
        when(messageProducerRecordMapper.updateById(any(MessageProducerRecord.class))).thenReturn(1);

        Date before = new Date();
        assertTrue(handler.handle(record(4)));

        verify(delayQueueContext).sendMessage(anyString(), anyString(), eq(1L), eq(TimeUnit.SECONDS));
        MessageProducerRecord updated = captureUpdate();
        assertEquals(5, updated.getRetryCount());
        // 退避封顶 3600 秒：60 * 2^4 = 960 秒
        long gapSeconds = (updated.getNextRetryTime().getTime() - before.getTime()) / 1000L;
        assertTrue(gapSeconds >= 959 && gapSeconds <= 965, "退避时间应约为 960 秒，实际 " + gapSeconds);
    }

    @Test
    void 超过上限转对账有问题且不再投递() {
        when(messageProducerRecordMapper.updateById(any(MessageProducerRecord.class))).thenReturn(1);

        assertTrue(handler.handle(record(5)));

        verify(delayQueueContext, never()).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        MessageProducerRecord updated = captureUpdate();
        assertEquals(ReconciliationStatus.RECONCILIATION_FAIL.getCode(), updated.getReconciliationStatus());
        assertNull(updated.getNextRetryTime());
        assertTrue(updated.getMessageSendException().contains("转人工处理"));
        assertEquals(1d, counter(Metrics.RESULT_DEAD));
    }

    @Test
    void 投递异常仍排下次并记重试指标() {
        when(messageProducerRecordMapper.updateById(any(MessageProducerRecord.class))).thenReturn(1);
        doThrow(new RuntimeException("redis down"))
                .when(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        assertTrue(handler.handle(record(1)));

        MessageProducerRecord updated = captureUpdate();
        assertEquals(MessageSendStatus.SEND_FAIL.getCode(), updated.getMessageSendStatus());
        assertEquals(2, updated.getRetryCount());
        assertNotNull(updated.getNextRetryTime());
        assertEquals("redis down", updated.getMessageSendException());
        assertEquals(1d, counter(Metrics.RESULT_RETRY));
    }

    @Test
    void 异常信息按字段长度截断() {
        when(messageProducerRecordMapper.updateById(any(MessageProducerRecord.class))).thenReturn(1);
        String longMessage = "x".repeat(400);
        doThrow(new RuntimeException(longMessage))
                .when(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        assertTrue(handler.handle(record(null)));

        MessageProducerRecord updated = captureUpdate();
        assertEquals(250, updated.getMessageSendException().length());
    }
}
