package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CreateOrderConsumer 消费超时丢弃测试：
 * 1. 延迟超过 MESSAGE_DELAY_TIME 的消息：丢弃前回滚 Redis 座位（rollbackProgramSeatByDiscard）
 *    并写入 DISCARD_ORDER + Prometheus 计数
 * 2. 延迟未超时的消息：走正常建单（createMq），不回滚
 * 3. 回滚失败不阻断丢弃记录写入
 *
 * 说明：SpringUtil 静态容器需 @BeforeAll 初始化（createRedisKey 依赖前缀）。
 */
class CreateOrderConsumerTest {

    private OrderService orderService;
    private RedisCache redisCache;
    private MeterRegistry meterRegistry;
    private CreateOrderConsumer createOrderConsumer;

    private static final String TOPIC = "create_order";
    private static final Long ORDER_NUMBER = 1001L;
    private static final Long USER_ID = 1L;
    private static final Long PROGRAM_ID = 10L;
    private static final Long IDENTIFIER_ID = 99L;
    private static final Long TICKET_CATEGORY_ID = 200L;
    private static final Long SEAT_ID = 3000L;
    private static final Long TICKET_USER_ID = 4000L;

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
        orderService = mock(OrderService.class);
        redisCache = mock(RedisCache.class);
        meterRegistry = mock(MeterRegistry.class);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        createOrderConsumer = new CreateOrderConsumer(orderService, redisCache, meterRegistry);
    }

    private OrderCreateMq buildOrderCreateMq(Date createOrderTime) {
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        orderCreateMq.setIdentifierId(IDENTIFIER_ID);
        orderCreateMq.setOrderNumber(ORDER_NUMBER);
        orderCreateMq.setProgramId(PROGRAM_ID);
        orderCreateMq.setUserId(USER_ID);
        orderCreateMq.setCreateOrderTime(createOrderTime);
        orderCreateMq.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
        OrderTicketUserCreateDto dto = new OrderTicketUserCreateDto();
        dto.setOrderNumber(ORDER_NUMBER);
        dto.setProgramId(PROGRAM_ID);
        dto.setUserId(USER_ID);
        dto.setTicketUserId(TICKET_USER_ID);
        dto.setSeatId(SEAT_ID);
        dto.setSeatInfo("A-1-1");
        dto.setTicketCategoryId(TICKET_CATEGORY_ID);
        dto.setOrderPrice(new BigDecimal("100.00"));
        dto.setCreateOrderTime(createOrderTime);
        orderCreateMq.setOrderTicketUserCreateDtoList(List.of(dto));
        return orderCreateMq;
    }

    private ConsumerRecord<String, String> buildConsumerRecord(OrderCreateMq orderCreateMq) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, String.valueOf(ORDER_NUMBER),
                JSON.toJSONString(orderCreateMq));
    }

    @Test
    void 超时消息丢弃前回滚Redis座位并写入丢弃记录() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 61000L));
        createOrderConsumer.consumerOrderMessage(List.of(buildConsumerRecord(orderCreateMq)));

        ArgumentCaptor<OrderCreateMq> rollbackCaptor = ArgumentCaptor.forClass(OrderCreateMq.class);
        verify(orderService).rollbackProgramSeatByDiscard(rollbackCaptor.capture());
        assertEquals(ORDER_NUMBER, rollbackCaptor.getValue().getOrderNumber());
        assertEquals(PROGRAM_ID, rollbackCaptor.getValue().getProgramId());

        // 超时消息不进入批量建单
        verify(orderService, never()).createMqBatch(anyList());

        ArgumentCaptor<DiscardOrder> discardCaptor = ArgumentCaptor.forClass(DiscardOrder.class);
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                discardCaptor.capture());
        assertEquals(ORDER_NUMBER, discardCaptor.getValue().getOrderCreateMq().getOrderNumber());
        assertEquals(DiscardOrderReason.CONSUMER_DELAY.getCode(), discardCaptor.getValue().getDiscardOrderReason());
    }

    @Test
    void 未超时消息不触发回滚直接批量建单() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        when(orderService.createMqBatch(anyList())).thenReturn(List.of());

        createOrderConsumer.consumerOrderMessage(List.of(buildConsumerRecord(orderCreateMq)));

        verify(orderService).createMqBatch(argThat(list -> list.size() == 1
                && ((OrderCreateMq) list.get(0)).getOrderNumber().equals(ORDER_NUMBER)));
        verify(orderService, never()).rollbackProgramSeatByDiscard(any());
        verify(redisCache, never()).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)), any());
    }

    @Test
    void 批量建单失败订单写入丢弃记录() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        when(orderService.createMqBatch(anyList())).thenReturn(List.of(orderCreateMq));

        createOrderConsumer.consumerOrderMessage(List.of(buildConsumerRecord(orderCreateMq)));

        ArgumentCaptor<DiscardOrder> discardCaptor = ArgumentCaptor.forClass(DiscardOrder.class);
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                discardCaptor.capture());
        assertEquals(ORDER_NUMBER, discardCaptor.getValue().getOrderCreateMq().getOrderNumber());
        assertEquals(DiscardOrderReason.CREATE_ORDER_FAIL.getCode(), discardCaptor.getValue().getDiscardOrderReason());
    }

    @Test
    void 回滚异常不阻断丢弃记录写入() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 61000L));
        doThrow(new RuntimeException("回滚失败")).when(orderService).rollbackProgramSeatByDiscard(any());

        createOrderConsumer.consumerOrderMessage(List.of(buildConsumerRecord(orderCreateMq)));

        verify(orderService).rollbackProgramSeatByDiscard(any());
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                any(DiscardOrder.class));
        verify(orderService, never()).createMqBatch(anyList());
    }
}
