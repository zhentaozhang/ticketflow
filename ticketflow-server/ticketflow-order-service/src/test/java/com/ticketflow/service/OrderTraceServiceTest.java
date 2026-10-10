package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ticketflow.domain.OrderTraceResult;
import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.enums.RecordType;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.redis.RedisCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.ticketflow.core.SpringUtil;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单笔订单排查视图测试（从 OrderService 拆出来的那一块）。
 * <p>
 * 这些用例盯的是“结论对不对”：五处记录拼起来之后，给出的 hinto 必须准确 ——
 * 运维/排查全靠它一眼看出这一单停在哪一步，说错了比不说更糟。
 */
class OrderTraceServiceTest {

    private OrderTraceService orderTraceService;
    private OrderMapper orderMapper;
    private RedisCache redisCache;

    private static final Long ORDER_NUMBER = 1001L;
    private static final Long USER_ID = 1L;
    private static final Long IDENTIFIER_ID = 99L;

    @BeforeAll
    static void initSpringUtil() {
        // RedisKeyBuild.createRedisKey 依赖 SpringUtil 里的前缀名
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
        orderMapper = mock(OrderMapper.class);
        redisCache = mock(RedisCache.class);
        orderTraceService = new OrderTraceService();
        ReflectionTestUtils.setField(orderTraceService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderTraceService, "redisCache", redisCache);
    }

    private Order buildOrder(Integer orderStatus) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(ORDER_NUMBER);
        order.setUserId(USER_ID);
        order.setProgramId(10L);
        order.setIdentifierId(IDENTIFIER_ID);
        order.setOrderPrice(new BigDecimal("100.00"));
        order.setOrderStatus(orderStatus);
        return order;
    }

    // ==================== 单笔订单排查视图 getOrderTrace ====================

    @Test
    void getOrderTrace订单不存在但有扣减流水时提示扣了没建() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 订单不存在时拿不到 programId，所以这一层只能看到“没有受理痕迹”
        OrderTraceResult trace = orderTraceService.getOrderTrace(ORDER_NUMBER);

        assertFalse(trace.getOrderExists());
        assertEquals("没有受理痕迹：这个订单号不是本系统产生的，或数据已被清理", trace.getHint());
        verify(redisCache).hasKey(any());
    }

    @Test
    void getOrderTrace已取消且未对账时提示不能排除用户已付款() {
        Order order = buildOrder(OrderStatus.CANCEL.getCode());
        order.setPayReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(redisCache.hasKey(any())).thenReturn(false);
        when(redisCache.getForHash(any(), anyString(), any())).thenReturn(null);
        when(redisCache.rangeForList(any(), anyLong(), anyLong(), any())).thenReturn(List.of());

        OrderTraceResult trace = orderTraceService.getOrderTrace(ORDER_NUMBER);

        assertTrue(trace.getOrderExists());
        assertEquals(OrderStatus.CANCEL.getCode(), trace.getOrderStatus());
        assertTrue(trace.getHint().contains("还没做过支付对账"), trace.getHint());
    }

    @Test
    void getOrderTrace未支付且有流水时给出完整链路() {
        Order order = buildOrder(OrderStatus.NO_PAY.getCode());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(redisCache.hasKey(any())).thenReturn(true);
        when(redisCache.getForHash(any(), anyString(), any())).thenReturn("{}");
        when(redisCache.rangeForList(any(), anyLong(), anyLong(), any())).thenReturn(List.of());

        OrderTraceResult trace = orderTraceService.getOrderTrace(ORDER_NUMBER);

        assertTrue(trace.getCreateMarkPresent());
        assertTrue(trace.getDeductRecordPresent());
        assertFalse(trace.getDiscarded());
        assertFalse(trace.getPending());
        // 流水 field = 类型_标识id_用户id（和 program-service 写入时保持一致）
        assertEquals(RecordType.REDUCE.getValue() + "_" + IDENTIFIER_ID + "_" + USER_ID, trace.getDeductRecordField());
        assertEquals("订单已创建、等待支付（超时会取消并回补）", trace.getHint());
    }

}
