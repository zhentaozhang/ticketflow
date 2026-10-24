package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.observability.Metrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款本地收尾测试。覆盖的是"钱已经退出去、本地状态却没落"这条最容易造成账实不符的路径：
 * <ul>
 *   <li>CAS 命中 → 主表置已退单 + 退款时间 + 对账标记，子表同步置已退单（释放限购额度）；</li>
 *   <li>CAS 未命中但订单已是已退单 → 重复回调/重试，幂等返回成功，不重复写；</li>
 *   <li>CAS 未命中且订单不是已取消 → 返回 false，调用方必须让渠道/对账重试，而不是当作成功吞掉；</li>
 *   <li>子表一条都没更新 → 主表已推进，仍返回成功（钱已退，不能回滚主表），只留告警。</li>
 * </ul>
 * 注：CAS 的 SQL 条件在单测里看不到（没有 MyBatis 实体元数据），这里保证"带条件而非裸 update"，
 * 条件本身（order_number + order_status=已取消）由 OrderRefundSettleService 的 lambdaUpdate 明确写出。
 */
class OrderRefundSettleServiceTest {

    private static final Long ORDER_NUMBER = 10001L;

    private OrderRefundSettleService orderRefundSettleService;
    private OrderMapper orderMapper;
    private OrderTicketUserMapper orderTicketUserMapper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        orderRefundSettleService = new OrderRefundSettleService();
        orderMapper = mock(OrderMapper.class);
        orderTicketUserMapper = mock(OrderTicketUserMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(orderRefundSettleService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderRefundSettleService, "orderTicketUserMapper", orderTicketUserMapper);
        ReflectionTestUtils.setField(orderRefundSettleService, "meterRegistry", meterRegistry);
    }

    @Test
    void settleRefund_CAS命中时置已退单并同步子表() {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(Wrapper.class))).thenReturn(3);

        assertTrue(orderRefundSettleService.settleRefund(ORDER_NUMBER));

        // 主表：已退单 + 退款时间 + 对账已处理，且必须是带条件的更新
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<Wrapper<Order>> orderWrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).update(orderCaptor.capture(), orderWrapperCaptor.capture());
        assertNotNull(orderWrapperCaptor.getValue());
        Order updateOrder = orderCaptor.getValue();
        assertEquals(OrderStatus.REFUND.getCode(), updateOrder.getOrderStatus());
        assertNotNull(updateOrder.getRefundOrderTime());
        assertEquals(ReconciliationStatus.RECONCILIATION_FINISH.getCode(), updateOrder.getPayReconciliationStatus());
        assertNotNull(updateOrder.getEditTime());

        // 子表：同一事务里按 order_number + 已取消置已退单，限购额度才会释放
        ArgumentCaptor<OrderTicketUser> ticketUserCaptor = ArgumentCaptor.forClass(OrderTicketUser.class);
        ArgumentCaptor<Wrapper<OrderTicketUser>> ticketWrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderTicketUserMapper).update(ticketUserCaptor.capture(), ticketWrapperCaptor.capture());
        assertNotNull(ticketWrapperCaptor.getValue());
        assertEquals(OrderStatus.REFUND.getCode(), ticketUserCaptor.getValue().getOrderStatus());
        assertNotNull(ticketUserCaptor.getValue().getEditTime());
        assertEquals(1.0, settleCounter(Metrics.RESULT_SUCCESS));
        assertEquals(0.0, settleCounter(Metrics.RESULT_IDEMPOTENT));
    }

    @Test
    void settleRefund_CAS未命中但订单已是已退单时幂等返回成功() {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.REFUND.getCode()));

        assertTrue(orderRefundSettleService.settleRefund(ORDER_NUMBER));

        // 幂等命中不需要再动子表（上一次已经写过了）
        verify(orderTicketUserMapper, never()).update(any(OrderTicketUser.class), any(Wrapper.class));
        assertEquals(1.0, settleCounter(Metrics.RESULT_IDEMPOTENT));
        assertEquals(0.0, settleCounter(Metrics.RESULT_ERROR));
    }

    @Test
    void settleRefund_CAS未命中且订单仍是已取消时返回失败等待重试() {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));

        // 钱已退但本地既没推进也不是已退单：必须报失败让渠道/对账下一轮再来，不能静默吞掉
        assertFalse(orderRefundSettleService.settleRefund(ORDER_NUMBER));
        verify(orderTicketUserMapper, never()).update(any(OrderTicketUser.class), any(Wrapper.class));
        assertEquals(1.0, settleCounter(Metrics.RESULT_ERROR));
    }

    @Test
    void settleRefund_CAS未命中且复查抛异常时返回失败() {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
        when(orderMapper.selectOne(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        assertFalse(orderRefundSettleService.settleRefund(ORDER_NUMBER));
        assertEquals(1.0, settleCounter(Metrics.RESULT_ERROR));
    }

    @Test
    void settleRefund_子表没有已取消记录时仍返回成功只告警() {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(Wrapper.class))).thenReturn(0);

        // 主表已推进、钱已退：不能因为子表 0 行就抛异常回滚掉主表状态
        assertTrue(orderRefundSettleService.settleRefund(ORDER_NUMBER));
        assertEquals(1.0, settleCounter(Metrics.RESULT_SUCCESS));
    }

    private Order buildOrder(Integer orderStatus) {
        Order order = new Order();
        order.setOrderNumber(ORDER_NUMBER);
        order.setOrderStatus(orderStatus);
        order.setRefundOrderTime(new Date());
        return order;
    }

    private double settleCounter(String resultTag) {
        // 用 find 而不是 get：没被累加过的 tag 不应该凭空造出一个计数器
        Counter counter = meterRegistry.find(Metrics.ORDER_REFUND_SETTLE_TOTAL)
                .tag(Metrics.RESULT, resultTag)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
