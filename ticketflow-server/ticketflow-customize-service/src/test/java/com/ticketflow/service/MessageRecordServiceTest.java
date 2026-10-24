package com.ticketflow.service;

import com.ticketflow.entity.MessageConsumerRecord;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageConsumerStatus;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.handler.ExceptionMessageHandler;
import com.ticketflow.handler.ExceptionMessageHandlerContext;
import com.ticketflow.mapper.MessageConsumerRecordMapper;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.reconciliation.ReconciliationTaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息对账的分流：消费成功即算送达，不再要求「发送状态也成功」。
 *
 * 旧实现只在「发送成功 + 消费成功」时才标对账成功，于是「发送失败 + 消费成功」
 * 这种消息（首次投递超时/异常，消费者其实收到并处理了）既不重投也不标成功，
 * 永远卡在未对账，每分钟被扫一遍。
 */
@ExtendWith(MockitoExtension.class)
class MessageRecordServiceTest {

    @Mock
    private MessageProducerRecordMapper messageProducerRecordMapper;

    @Mock
    private MessageConsumerRecordMapper messageConsumerRecordMapper;

    @Mock
    private ExceptionMessageHandlerContext exceptionMessageHandlerContext;

    @Mock
    private ReconciliationTaskQueue reconciliationTaskQueue;

    @Mock
    private MessageProducerRecordService messageProducerRecordService;

    @Mock
    private ExceptionMessageHandler exceptionMessageHandler;

    private MessageRecordService messageRecordService;

    @BeforeEach
    void setUp() {
        messageRecordService = new MessageRecordService();
        ReflectionTestUtils.setField(messageRecordService, "messageProducerRecordMapper", messageProducerRecordMapper);
        ReflectionTestUtils.setField(messageRecordService, "messageConsumerRecordMapper", messageConsumerRecordMapper);
        ReflectionTestUtils.setField(messageRecordService, "exceptionMessageHandlerContext", exceptionMessageHandlerContext);
        ReflectionTestUtils.setField(messageRecordService, "reconciliationTaskQueue", reconciliationTaskQueue);
        ReflectionTestUtils.setField(messageRecordService, "messageProducerRecordService", messageProducerRecordService);
    }

    private MessageProducerRecord producerRecord() {
        MessageProducerRecord record = new MessageProducerRecord();
        record.setId(1001L);
        record.setMessageId(2001L);
        record.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
        record.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
        record.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        return record;
    }

    private MessageConsumerRecord consumerRecord(Integer consumerStatus) {
        MessageConsumerRecord record = new MessageConsumerRecord();
        record.setId(3001L);
        record.setMessageId(2001L);
        record.setMessageConsumerStatus(consumerStatus);
        return record;
    }

    @Test
    void 发送失败但消费成功时标记对账成功且不重投() {
        MessageProducerRecord producerRecord = producerRecord();
        MessageConsumerRecord consumerRecord = consumerRecord(MessageConsumerStatus.CONSUMER_SUCCESS.getCode());
        when(exceptionMessageHandlerContext.getExceptionMessageHandler(any()))
                .thenReturn(exceptionMessageHandler);
        when(exceptionMessageHandler.noReconciliationMessageProducerRecordList())
                .thenReturn(List.of(producerRecord));
        when(messageConsumerRecordMapper.selectList(any())).thenReturn(List.of(consumerRecord));

        assertTrue(messageRecordService.executeReconciliationTask());

        verify(messageProducerRecordService).updateToReconciliationSuccess(producerRecord, consumerRecord);
        verify(reconciliationTaskQueue, never()).putTask(any());
    }

    @Test
    void 消费失败时入重投队列且不写终态() {
        MessageProducerRecord producerRecord = producerRecord();
        MessageConsumerRecord consumerRecord = consumerRecord(MessageConsumerStatus.CONSUMER_FAIL.getCode());
        when(exceptionMessageHandlerContext.getExceptionMessageHandler(any()))
                .thenReturn(exceptionMessageHandler);
        when(exceptionMessageHandler.noReconciliationMessageProducerRecordList())
                .thenReturn(List.of(producerRecord));
        when(messageConsumerRecordMapper.selectList(any())).thenReturn(List.of(consumerRecord));

        assertTrue(messageRecordService.executeReconciliationTask());

        ArgumentCaptor<com.ticketflow.reconciliation.ReconciliationTask> captor =
                ArgumentCaptor.forClass(com.ticketflow.reconciliation.ReconciliationTask.class);
        verify(reconciliationTaskQueue, times(1)).putTask(captor.capture());
        verify(messageProducerRecordService, never()).updateToReconciliationSuccess(any(), any());
    }

    @Test
    void 没有消费记录时同样入重投队列() {
        MessageProducerRecord producerRecord = producerRecord();
        when(exceptionMessageHandlerContext.getExceptionMessageHandler(any()))
                .thenReturn(exceptionMessageHandler);
        when(exceptionMessageHandler.noReconciliationMessageProducerRecordList())
                .thenReturn(List.of(producerRecord));
        when(messageConsumerRecordMapper.selectList(any())).thenReturn(List.of());

        assertTrue(messageRecordService.executeReconciliationTask());

        verify(reconciliationTaskQueue).putTask(any());
        verify(messageProducerRecordService, never()).updateToReconciliationSuccess(any(), any());
    }

    @Test
    void 人工重投前把自动重投次数清零() {
        MessageProducerRecord producerRecord = producerRecord();
        producerRecord.setRetryCount(5);
        producerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_FAIL.getCode());
        when(messageProducerRecordMapper.selectOne(any())).thenReturn(producerRecord);
        when(exceptionMessageHandlerContext.getExceptionMessageHandler(eq(MessageType.DELAY_ORDER_CANCEL)))
                .thenReturn(exceptionMessageHandler);
        when(exceptionMessageHandler.handle(any())).thenReturn(true);

        com.ticketflow.dto.ExecuteExceptionMessageDto dto = new com.ticketflow.dto.ExecuteExceptionMessageDto();
        dto.setMessageId(2001L);
        assertTrue(messageRecordService.executeExceptionMessage(dto));

        ArgumentCaptor<MessageProducerRecord> captor = ArgumentCaptor.forClass(MessageProducerRecord.class);
        verify(messageProducerRecordMapper).updateById(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(0, captor.getValue().getRetryCount());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getNextRetryTime());
        verify(exceptionMessageHandler).handle(producerRecord);
    }
}
