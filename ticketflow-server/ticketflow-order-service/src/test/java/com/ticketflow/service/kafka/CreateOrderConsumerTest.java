package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CreateOrderConsumer 消费测试：
 * 1. 延迟超过 MESSAGE_DELAY_TIME 的消息：丢弃前回滚 Redis 座位（rollbackProgramSeatByDiscard）
 *    并写入 DISCARD_ORDER + Prometheus 计数
 * 2. 延迟未超时的消息：走正常建单（createMq），不回滚
 * 3. 回滚失败不阻断丢弃记录写入
 * 4. 建单失败不再被吞掉，异常上抛给容器的错误处理器（重试/留痕由它决定）
 * 5. 重试穷尽的恢复回调（CreateOrderDiscardRecorder）：留痕 + 指标，且自身不抛异常
 *
 * 说明：SpringUtil 静态容器需 @BeforeAll 初始化（createRedisKey 依赖前缀）。
 */
class CreateOrderConsumerTest {

    private OrderService orderService;
    private RedisCache redisCache;
    private MeterRegistry meterRegistry;
    private CreateOrderDiscardRecorder discardRecorder;
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
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(mock(Timer.class));
        discardRecorder = new CreateOrderDiscardRecorder(redisCache, meterRegistry);
        createOrderConsumer = new CreateOrderConsumer(orderService, discardRecorder, meterRegistry);
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
    void 重试总时长必须小于消费端丢弃闸() {
        // 两个数是一对耦合：重试总时长必须留在丢弃闸以内，
        // 否则“重试”本身会把消息拖老，反而更容易撞上丢弃、把可恢复的故障变成丢单。
        assertTrue(OrderKafkaErrorHandlerConfig.RETRY_MAX_ELAPSED_MS < CreateOrderConsumer.MESSAGE_DELAY_TIME,
                "重试总时长(" + OrderKafkaErrorHandlerConfig.RETRY_MAX_ELAPSED_MS
                        + "ms) 必须小于丢弃闸(" + CreateOrderConsumer.MESSAGE_DELAY_TIME + "ms)");
    }

    @Test
    void 超时消息丢弃前回滚Redis座位并写入丢弃记录() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 61000L));
        createOrderConsumer.consumerOrderMessage(buildConsumerRecord(orderCreateMq));

        ArgumentCaptor<OrderCreateMq> rollbackCaptor = ArgumentCaptor.forClass(OrderCreateMq.class);
        verify(orderService).rollbackProgramSeatByDiscard(rollbackCaptor.capture());
        assertEquals(ORDER_NUMBER, rollbackCaptor.getValue().getOrderNumber());
        assertEquals(PROGRAM_ID, rollbackCaptor.getValue().getProgramId());

        verify(orderService, never()).createMq(any());

        ArgumentCaptor<DiscardOrder> discardCaptor = ArgumentCaptor.forClass(DiscardOrder.class);
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                discardCaptor.capture());
        assertEquals(ORDER_NUMBER, discardCaptor.getValue().getOrderCreateMq().getOrderNumber());
        assertEquals(DiscardOrderReason.CONSUMER_DELAY.getCode(), discardCaptor.getValue().getDiscardOrderReason());
    }

    @Test
    void 未超时消息不触发回滚直接建单() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        createOrderConsumer.consumerOrderMessage(buildConsumerRecord(orderCreateMq));

        verify(orderService).createMq(orderCreateMq);
        verify(orderService, never()).rollbackProgramSeatByDiscard(any());
        verify(redisCache, never()).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)), any());
        // 建单成功要计“落库数”（端到端落库率的分子）
        verify(meterRegistry).counter(eq(Metrics.ORDER_CREATED_TOTAL), eq(Metrics.VERSION), anyString());
    }

    @Test
    void 消费延迟会记成指标() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        createOrderConsumer.consumerOrderMessage(buildConsumerRecord(orderCreateMq));

        // 消费延迟是“离 60 秒丢弃闸还有多远”的直接度量
        verify(meterRegistry).timer(eq(Metrics.ORDER_CREATE_DELAY_SECONDS), eq(Metrics.VERSION), anyString());
    }

    @Test
    void 回滚异常不阻断丢弃记录写入() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 61000L));
        doThrow(new RuntimeException("回滚失败")).when(orderService).rollbackProgramSeatByDiscard(any());

        createOrderConsumer.consumerOrderMessage(buildConsumerRecord(orderCreateMq));

        verify(orderService).rollbackProgramSeatByDiscard(any());
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                any(DiscardOrder.class));
        verify(orderService, never()).createMq(any());
    }

    @Test
    void 未超时消息建单失败时异常上抛给错误处理器() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        TicketFlowFrameException businessException = new TicketFlowFrameException(BaseCode.SEAT_IS_NOT_NOT_SOLD);
        when(orderService.createMq(orderCreateMq)).thenThrow(businessException);

        // 不再在这里吞异常（吞掉会让 offset 照常提交，等于瞬时故障直接变成用户丢单），
        // 交给容器的 DefaultErrorHandler 决定重试还是留痕
        TicketFlowFrameException thrown = assertThrows(TicketFlowFrameException.class,
                () -> createOrderConsumer.consumerOrderMessage(buildConsumerRecord(orderCreateMq)));

        assertSame(businessException, thrown);
        // 建单失败时消费者不再直接写丢弃记录：这一步已经挪到错误处理器的恢复回调里
        verify(redisCache, never()).leftPushForList(any(), any());
    }

    @Test
    void 重试穷尽后写丢弃记录并上报指标() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));

        discardRecorder.accept(buildConsumerRecord(orderCreateMq), new RuntimeException("Feign 超时"));

        ArgumentCaptor<DiscardOrder> discardCaptor = ArgumentCaptor.forClass(DiscardOrder.class);
        verify(redisCache).leftPushForList(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, PROGRAM_ID)),
                discardCaptor.capture());
        assertEquals(ORDER_NUMBER, discardCaptor.getValue().getOrderCreateMq().getOrderNumber());
        assertEquals(DiscardOrderReason.CREATE_ORDER_FAIL.getCode(), discardCaptor.getValue().getDiscardOrderReason());
        verify(meterRegistry).counter(eq(Metrics.ORDER_CREATE_FAIL_TOTAL),
                eq(Metrics.REASON), eq(Metrics.REASON_CREATE_ORDER_FAIL),
                eq(Metrics.PROGRAM_ID), eq(String.valueOf(PROGRAM_ID)));
    }

    @Test
    void 重试穷尽后消息体无法解析时只记日志不抛异常() {
        ConsumerRecord<String, String> brokenRecord =
                new ConsumerRecord<>(TOPIC, 0, 0L, String.valueOf(ORDER_NUMBER), "not-a-json");

        assertDoesNotThrow(() -> discardRecorder.accept(brokenRecord, new RuntimeException("处理失败")));
        verify(redisCache, never()).leftPushForList(any(), any());
    }

    @Test
    void 重试穷尽后留痕失败不抛异常避免分区卡死() {
        OrderCreateMq orderCreateMq = buildOrderCreateMq(new Date(System.currentTimeMillis() - 1000L));
        doThrow(new RuntimeException("redis 不可用")).when(redisCache).leftPushForList(any(), any());

        assertDoesNotThrow(() -> discardRecorder.accept(buildConsumerRecord(orderCreateMq),
                new RuntimeException("处理失败")));
    }
}
