package com.ticketflow.scheduletask;

import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.PaymentReconcileResult;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.observability.Metrics;
import com.ticketflow.service.OrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.DateUtils;
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
        // 三轮扫描：新鲜窗 → 窗口外存量 → 退款未确认；这里只有新鲜窗有单
        when(orderMapper.selectList(any()))
                .thenReturn(List.of(buildCancelledOrder()), List.of(), List.of());
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(ORDER_NUMBER,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("not_paid"));
    }

    @Test
    void 渠道已支付并退款成功时标记对账完成并上报资损信号() {
        // 第一段扫「未对账」的取消单，第二段扫「退款未确认」的单：这里第二轮为空
        when(orderMapper.selectList(any())).thenReturn(List.of(buildCancelledOrder()), List.of());
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
        when(orderMapper.selectList(any())).thenReturn(List.of(first, second), List.of(), List.of());
        when(orderService.reconcilePayment(first)).thenThrow(new RuntimeException("渠道超时"));
        when(orderService.reconcilePayment(second)).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(2002L,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(orderService, never()).markPaymentReconciled(eq(ORDER_NUMBER), any());
    }

    // ==================== 第二段：退款已受理但未确认到账的重试 ====================

    @Test
    void 退款仍未确认到账时重新驱动渠道退款且不标记对账完成() {
        when(orderMapper.selectList(any()))
                .thenReturn(List.of())      // 第一段 A：新鲜窗，没有新取消的单
                .thenReturn(List.of())      // 第一段 B：窗口外存量也排空了
                .thenReturn(List.of(buildCancelledOrder()));  // 第二段：退款未确认的单
        when(orderService.reconfirmRefund(any(Order.class))).thenReturn(false);
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.REFUND_FAILED);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).reconfirmRefund(any(Order.class));
        verify(orderService, timeout(3000)).reconcilePayment(any(Order.class));
        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("refund_refund_failed"));
        // 没确认到账就不能标记完成，否则这单再也不会被扫出来
        verify(orderService, never()).markPaymentReconciled(any(), any());
    }

    @Test
    void 退款单已确认到账时先收尾并标记对账完成不再驱动退款() {
        when(orderMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(buildCancelledOrder()));
        when(orderService.reconfirmRefund(any(Order.class))).thenReturn(true);

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, timeout(3000)).markPaymentReconciled(ORDER_NUMBER,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        // 已确认到账就不要再碰渠道的退款写接口
        verify(orderService, never()).reconcilePayment(any());
    }

    @Test
    void 退款超窗仍未确认时告警等人核对() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(3L);

        paymentReconciliationTask.paymentReconciliation();

        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_UNCONFIRMED));
        verify(orderService, never()).reconcilePayment(any());
    }

    // ==================== 第一段 B：窗口外存量补扫（不再是「过了窗口就永久放弃」） ====================

    @Test
    void 窗口外未对账的取消单也会被补扫并标记完成() {
        Order oldOrder = buildCancelledOrder();
        oldOrder.setOrderNumber(3003L);
        oldOrder.setCancelOrderTime(DateUtils.addHour(DateUtils.now(), -6));
        when(orderMapper.selectList(any()))
                .thenReturn(List.of())               // 新鲜窗空
                .thenReturn(List.of(oldOrder),      // 存量：6 小时前取消、一直没对账
                        List.of());                  // 退款未确认段
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        // 关键：窗口外的单照样被核对、照样标记完成（否则它就是静默差异）
        verify(orderService, timeout(3000)).markPaymentReconciled(3003L,
                ReconciliationStatus.RECONCILIATION_SUCCESS);
        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.PAYMENT_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq("backlog_not_paid"));
    }

    @Test
    void 新鲜窗没排空时不推进存量() {
        // 新鲜窗整整一批（>= BATCH_SIZE=50）没扫完，说明系统正跟不上，此时不能再去啃存量
        List<Order> fresh = new java.util.ArrayList<>();
        for (int index = 0; index < 50; index++) {
            Order order = buildCancelledOrder();
            order.setOrderNumber((long) index + 1);
            fresh.add(order);
        }
        when(orderMapper.selectList(any())).thenReturn(fresh, List.of());
        when(orderService.reconcilePayment(any(Order.class))).thenReturn(PaymentReconcileResult.NOT_PAID);

        paymentReconciliationTask.paymentReconciliation();

        // 只处理了新鲜窗那 50 笔，存量段没有启动（否则总笔数会超过 50）
        verify(orderService, timeout(3000).times(50)).reconcilePayment(any());
    }

    @Test
    void 没有候选订单时什么都不做() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        paymentReconciliationTask.paymentReconciliation();

        verify(orderService, never()).reconcilePayment(any());
    }
}
