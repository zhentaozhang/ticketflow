package com.ticketflow.service.delayconsumer;

import com.alibaba.fastjson2.JSON;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.InsertMessageConsumerRecordDto;
import com.ticketflow.dto.MessageIdDto;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.dto.UpdateMessageConsumerRecordDto;
import com.ticketflow.enums.MessageConsumerStatus;
import com.ticketflow.module.DelayOrderCancelMessageModule;
import com.ticketflow.service.OrderService;
import com.ticketflow.vo.MessageConsumerRecordVo;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延迟订单取消消费者测试。
 * 覆盖：api-data 查询/插入消费记录失败或异常时不阻断取消（方案A）、
 * 正常流程、已消费成功跳过
 */
class DelayOrderCancelConsumerTest {

    private DelayOrderCancelConsumer delayOrderCancelConsumer;

    private OrderService orderService;

    private ApiDataClient apiDataClient;

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
        delayOrderCancelConsumer = new DelayOrderCancelConsumer();
        orderService = mock(OrderService.class);
        apiDataClient = mock(ApiDataClient.class);
        ReflectionTestUtils.setField(delayOrderCancelConsumer, "orderService", orderService);
        ReflectionTestUtils.setField(delayOrderCancelConsumer, "apiDataClient", apiDataClient);
    }

    private String buildContent() {
        DelayOrderCancelMessageModule module = new DelayOrderCancelMessageModule();
        module.setMessageTraceId(1L);
        module.setMessageId(2L);
        module.setProgramId(3L);
        module.setOrderNumber(4L);
        return JSON.toJSONString(module);
    }

    @Test
    void 正常流程消费成功并更新记录() {
        when(apiDataClient.getMessageConsumerByMessageId(any(MessageIdDto.class))).thenReturn(ApiResponse.ok(null));
        MessageConsumerRecordVo recordVo = new MessageConsumerRecordVo();
        recordVo.setId(9L);
        recordVo.setMessageConsumerCount(1);
        when(apiDataClient.insertMessageConsumerRecord(any(InsertMessageConsumerRecordDto.class)))
                .thenReturn(ApiResponse.ok(recordVo));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        delayOrderCancelConsumer.execute(buildContent());

        ArgumentCaptor<UpdateMessageConsumerRecordDto> captor = ArgumentCaptor.forClass(UpdateMessageConsumerRecordDto.class);
        verify(apiDataClient).updateMessageConsumerRecord(captor.capture());
        assertEquals(9L, captor.getValue().getId());
        assertEquals(MessageConsumerStatus.CONSUMER_SUCCESS.getCode(), captor.getValue().getMessageConsumerStatus());
    }

    @Test
    void 查询消费记录业务码失败时仍执行取消() {
        when(apiDataClient.getMessageConsumerByMessageId(any(MessageIdDto.class))).thenReturn(ApiResponse.error("查询失败"));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        delayOrderCancelConsumer.execute(buildContent());

        verify(orderService).cancel(any(OrderCancelDto.class));
    }

    @Test
    void 查询消费记录异常时仍执行取消() {
        doThrow(new RuntimeException("rpc异常")).when(apiDataClient).getMessageConsumerByMessageId(any(MessageIdDto.class));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        delayOrderCancelConsumer.execute(buildContent());

        verify(orderService).cancel(any(OrderCancelDto.class));
    }

    @Test
    void 插入消费记录业务码失败时仍执行取消() {
        when(apiDataClient.getMessageConsumerByMessageId(any(MessageIdDto.class))).thenReturn(ApiResponse.ok(null));
        when(apiDataClient.insertMessageConsumerRecord(any(InsertMessageConsumerRecordDto.class)))
                .thenReturn(ApiResponse.error("插入失败"));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        delayOrderCancelConsumer.execute(buildContent());

        verify(orderService).cancel(any(OrderCancelDto.class));
    }

    @Test
    void 插入消费记录异常时仍执行取消() {
        when(apiDataClient.getMessageConsumerByMessageId(any(MessageIdDto.class))).thenReturn(ApiResponse.ok(null));
        doThrow(new RuntimeException("rpc异常")).when(apiDataClient).insertMessageConsumerRecord(any(InsertMessageConsumerRecordDto.class));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        delayOrderCancelConsumer.execute(buildContent());

        verify(orderService).cancel(any(OrderCancelDto.class));
    }

    @Test
    void 已消费成功时不重复取消() {
        MessageConsumerRecordVo existRecordVo = new MessageConsumerRecordVo();
        existRecordVo.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_SUCCESS.getCode());
        when(apiDataClient.getMessageConsumerByMessageId(any(MessageIdDto.class))).thenReturn(ApiResponse.ok(existRecordVo));

        delayOrderCancelConsumer.execute(buildContent());

        verify(orderService, never()).cancel(any(OrderCancelDto.class));
    }
}
