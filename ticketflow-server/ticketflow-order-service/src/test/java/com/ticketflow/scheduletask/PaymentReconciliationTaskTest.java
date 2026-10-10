package com.ticketflow.scheduletask;

import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.PaymentReconcileResult;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.observability.Metrics;
import com.ticketflow.service.OrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付对账任务的调度与分流测试。
 * <p>
 * 这个任务存在的唯一理由是：回调丢失时会出现"用户付了钱、订单却被取消"，
 * 而这是唯一能发现它的地方。所以测试要盯住两件事：
 * 处理完毕的订单要标记掉（不能反复问渠道），处理失败的订单不能标记（要重试）。
 */
class PaymentReconciliationTaskTest {

    private OrderService orderService;
    private OrderMapper orderMapper;
    private MeterRegistry meterRegistry;
    private ServiceLockTool serviceLockTool;
    private RLock roundLock;
    private PaymentReconciliationTask paymentReconciliationTask;

    private static final Long ORDER_NUMBER = 1001L;

    @BeforeEach
    void setUp() throws Exception {
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        meterRegistry = mock(MeterRegistry.class);
        serviceLockTool = mock(ServiceLockTool.class);
        roundLock = mock(RLock.class);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));

        paymentReconciliationTask = new PaymentReconciliationTask();
        ReflectionTestUtils.setField(paymentReconciliationTask, "orderService", orderService);
        ReflectionTestUtils.setField(paymentReconciliationTask, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(paymentReconciliationTask, "meterRegistry", meterRegistry);
        ReflectionTestUtils.setField(paymentReconciliationTask, "serviceLockTool", serviceLockTool);
        // 多实例互斥锁：默认拿得到，走正常对账链路
        when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(roundLock);
        when(roundLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    private Order buildCancelledOrder() {
        Order order = new Order();
        order.setOrderNumber(ORDER_NUMBER);
        order.setOrderStatus(OrderStatus.CANCEL.getCode());
        return order;
    }

    @Test
    void 其它实例正在对账时本轮跳过不给渠道发重复请求() throws Exception {
        when(roundLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        paymentReconciliationTask.paymentReconciliation();

        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_SKIPPED));
        // 关键：这一轮不能去问渠道（否则多实例会把同一批订单问 N 遍）
        verify(orderMapper, never()).selectList(any());
        verify(orderService, never()).reconcilePayment(any());
        verify(roundLock, never()).unlock();
    }

    @Test
    void 渠道未支付的订单标记对账完成且不再重复核对() {
        when(orderMapper.selectList(any())).thenReturn(List.of(buildCancelledOrder()));
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(ORDER_NUMBER,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("not_paid"));
    }

    @Test
    void 渠道已支付并退款成功时标记对账完成并上报资损信号() {
        when(orderMapper.selectList(any())).thenReturn(List.of(buildCancelledOrder()));
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.REFUNDED);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(ORDER_NUMBER,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("refunded"));
    }

    @Test
    void 核对失败时不标记对账完成留给下一轮重试() {
        when(orderMapper.selectList(any())).thenReturn(List.of(buildCancelledOrder()));
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.REFUND_FAILED);

        paymentReconciliationTask.paymentReconciliation();

        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("refund_failed"));
        // 没标记 -> 下一轮还会被扫出来重试
        verify(orderService, never()).markPaymentReconciled(any(), any());
    }

    @Test
    void 单笔异常不影响后面的订单且不标记() {
        Order first = buildCancelledOrder();
        Order second = buildCancelledOrder();
        second.setOrderNumber(2002L);
        when(orderMapper.selectList(any())).thenReturn(List.of(first, second));
        when(orderService.reconcilePayment(first)).thenThrow(new RuntimeException("渠道超时"));
        when(orderService.reconcilePayment(second)).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(2002L,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(orderService, never()).markPaymentReconciled(eq(ORDER_NUMBER), any());
    }

    @Test
    void 没有候选订单时什么都不做() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, never()).reconcilePayment(any());
    }
}
