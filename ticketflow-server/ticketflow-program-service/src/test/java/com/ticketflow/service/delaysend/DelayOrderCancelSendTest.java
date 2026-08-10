package com.ticketflow.service.delaysend;

import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.DelayOrderCancelDto;
import com.ticketflow.dto.InsertMessageProducerRecordDto;
import com.ticketflow.dto.UpdateMessageProducerRecordDto;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.module.DelayOrderCancelMessageModule;
import com.ticketflow.vo.MessageProducerRecordVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延迟订单取消消息发送测试。
 * 覆盖：正常流程（insert → push → update SEND_SUCCESS）、
 * insert 失败/异常仍 push、push 失败记 SEND_FAIL、开关关闭不发送
 */
class DelayOrderCancelSendTest {

    private DelayOrderCancelSend delayOrderCancelSend;

    private ApiDataClient apiDataClient;

    private DelayQueueContext delayQueueContext;

    @BeforeAll
    static void initSpringUtil() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(mock(ConfigurableEnvironment.class));
        new SpringUtil().initialize(context);
    }

    @AfterAll
    static void clearSpringUtil() {
        new SpringUtil().initialize(null);
    }

    @BeforeEach
    void setUp() {
        delayOrderCancelSend = new DelayOrderCancelSend();
        UidGenerator uidGenerator = mock(UidGenerator.class);
        when(uidGenerator.getUid()).thenReturn(1000L);
        apiDataClient = mock(ApiDataClient.class);
        delayQueueContext = mock(DelayQueueContext.class);
        ReflectionTestUtils.setField(delayOrderCancelSend, "uidGenerator", uidGenerator);
        ReflectionTestUtils.setField(delayOrderCancelSend, "apiDataClient", apiDataClient);
        ReflectionTestUtils.setField(delayOrderCancelSend, "delayQueueContext", delayQueueContext);
        ReflectionTestUtils.setField(delayOrderCancelSend, "delayOrderCancel", true);
    }

    private DelayOrderCancelDto buildDto() {
        DelayOrderCancelDto dto = new DelayOrderCancelDto();
        dto.setProgramId(1L);
        dto.setOrderNumber(100L);
        return dto;
    }

    @Test
    void 正常流程push成功后更新发送日志为成功() {
        MessageProducerRecordVo recordVo = new MessageProducerRecordVo();
        recordVo.setId(9L);
        when(apiDataClient.insertMessageProducerRecord(any(InsertMessageProducerRecordDto.class)))
                .thenReturn(ApiResponse.ok(recordVo));

        delayOrderCancelSend.doSendMessage(buildDto());

        verify(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        ArgumentCaptor<UpdateMessageProducerRecordDto> captor = ArgumentCaptor.forClass(UpdateMessageProducerRecordDto.class);
        verify(apiDataClient).updateMessageProducerRecord(captor.capture());
        assertEquals(9L, captor.getValue().getId());
        assertEquals(MessageSendStatus.SEND_SUCCESS.getCode(), captor.getValue().getMessageSendStatus());
    }

    @Test
    void insert业务码失败时仍发送延迟消息且不更新日志() {
        when(apiDataClient.insertMessageProducerRecord(any(InsertMessageProducerRecordDto.class)))
                .thenReturn(ApiResponse.error("insert失败"));

        delayOrderCancelSend.doSendMessage(buildDto());

        verify(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(apiDataClient, never()).updateMessageProducerRecord(any(UpdateMessageProducerRecordDto.class));
    }

    @Test
    void insert异常时仍发送延迟消息且不更新日志() {
        doThrow(new RuntimeException("rpc异常")).when(apiDataClient)
                .insertMessageProducerRecord(any(InsertMessageProducerRecordDto.class));

        delayOrderCancelSend.doSendMessage(buildDto());

        verify(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(apiDataClient, never()).updateMessageProducerRecord(any(UpdateMessageProducerRecordDto.class));
    }

    @Test
    void push失败时更新发送日志为失败并记录异常() {
        MessageProducerRecordVo recordVo = new MessageProducerRecordVo();
        recordVo.setId(9L);
        when(apiDataClient.insertMessageProducerRecord(any(InsertMessageProducerRecordDto.class)))
                .thenReturn(ApiResponse.ok(recordVo));
        doThrow(new RuntimeException("redis异常")).when(delayQueueContext).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        delayOrderCancelSend.doSendMessage(buildDto());

        ArgumentCaptor<UpdateMessageProducerRecordDto> captor = ArgumentCaptor.forClass(UpdateMessageProducerRecordDto.class);
        verify(apiDataClient).updateMessageProducerRecord(captor.capture());
        assertEquals(MessageSendStatus.SEND_FAIL.getCode(), captor.getValue().getMessageSendStatus());
        assertEquals("redis异常", captor.getValue().getMessageSendException());
    }

    @Test
    void 开关关闭时不发送延迟消息() {
        ReflectionTestUtils.setField(delayOrderCancelSend, "delayOrderCancel", false);

        delayOrderCancelSend.sendMessage(buildDto());

        verify(apiDataClient, never()).insertMessageProducerRecord(any());
        verify(delayQueueContext, never()).sendMessage(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
