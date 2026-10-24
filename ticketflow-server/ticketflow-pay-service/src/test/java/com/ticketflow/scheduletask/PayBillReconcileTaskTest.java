package com.ticketflow.scheduletask;

import com.ticketflow.entity.PayBill;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.observability.Metrics;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.TradeResult;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayBillReconcileTaskTest {

    @Mock
    private PayBillMapper payBillMapper;

    @Mock
    private PayStrategyContext payStrategyContext;

    @Mock
    private PayStrategyHandler payStrategyHandler;

    @Mock
    private ServiceLockTool serviceLockTool;

    @Mock
    private MeterRegistry meterRegistry;

    @InjectMocks
    private PayBillReconcileTask payBillReconcileTask;

    @BeforeEach
    void setUp() {
        when(payStrategyContext.get(anyString())).thenReturn(payStrategyHandler);
        when(payBillMapper.update(any(), any())).thenReturn(1);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        ReflectionTestUtils.setField(payBillReconcileTask, "meterRegistry", meterRegistry);
    }

    private PayBill noPayBill() {
        PayBill payBill = new PayBill();
        payBill.setId(100L);
        payBill.setOutOrderNo("20260804000000000001");
        payBill.setPayChannel(PayChannel.ALIPAY.getValue());
        payBill.setPayAmount(new BigDecimal("100.00"));
        payBill.setPayBillStatus(PayBillStatus.NO_PAY.getCode());
        return payBill;
    }

    private TradeResult tradeResult(boolean success, PayBillStatus status, String amount) {
        TradeResult tradeResult = new TradeResult();
        tradeResult.setSuccess(success);
        tradeResult.setOutTradeNo("20260804000000000001");
        tradeResult.setPayBillStatus(status == null ? null : status.getCode());
        tradeResult.setTotalAmount(amount == null ? null : new BigDecimal(amount));
        return tradeResult;
    }

    private List<PayBill> bills(int size) {
        List<PayBill> list = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            PayBill payBill = noPayBill();
            payBill.setId((long) index + 1);
            payBill.setOutOrderNo("2026080400000000000" + index);
            list.add(payBill);
        }
        return list;
    }

    @Test
    void reconcileNoPayBills_没有候选账单_不查渠道() {
        when(payBillMapper.selectList(any())).thenReturn(Collections.emptyList());

        payBillReconcileTask.reconcileNoPayBills();

        verify(payStrategyHandler, never()).queryTrade(anyString());
        verify(payBillMapper, never()).update(any(), any());
    }

    @Test
    void reconcileNoPayBills_渠道确认已支付且金额一致_补正为已支付() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(true, PayBillStatus.PAY, "100.00"));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper).update(any(PayBill.class), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_PAID));
    }

    @Test
    void reconcileNoPayBills_渠道已支付但金额不一致_不改状态且要人工核对() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(true, PayBillStatus.PAY, "1.00"));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper, never()).update(any(), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_AMOUNT_MISMATCH));
    }

    @Test
    void reconcileNoPayBills_渠道交易已关闭_本地账单置已取消() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(true, PayBillStatus.CANCEL, null));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper).update(any(PayBill.class), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_CLOSED));
    }

    @Test
    void reconcileNoPayBills_渠道仍是待支付_不改状态() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(true, PayBillStatus.NO_PAY, null));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper, never()).update(any(), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_UNPAID));
    }

    @Test
    void reconcileNoPayBills_渠道查询返回失败_不改状态() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(false, null, null));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper, never()).update(any(), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_QUERY_FAILED));
    }

    @Test
    void reconcileNoPayBills_渠道查询抛异常_不改状态() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenThrow(new RuntimeException("渠道超时"));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payBillMapper, never()).update(any(), any());
        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_QUERY_FAILED));
    }

    @Test
    void reconcileNoPayBills_连续五笔查询失败_本轮提前结束() {
        when(payBillMapper.selectList(any())).thenReturn(bills(10));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(false, null, null));

        payBillReconcileTask.reconcileNoPayBills();

        verify(payStrategyHandler, times(5)).queryTrade(anyString());
    }

    @Test
    void reconcileNoPayBills_条件更新未命中_按并发处理() {
        when(payBillMapper.selectList(any())).thenReturn(List.of(noPayBill()));
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(true, PayBillStatus.PAY, "100.00"));
        when(payBillMapper.update(any(), any())).thenReturn(0);

        payBillReconcileTask.reconcileNoPayBills();

        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_CONCURRENT));
    }

    @Test
    void reconcileNoPayBills_查询候选账单异常_记录错误指标且不抛出() {
        when(payBillMapper.selectList(any())).thenThrow(new RuntimeException("库挂了"));

        assertDoesNotThrow(() -> payBillReconcileTask.reconcileNoPayBills());

        verify(meterRegistry).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_ERROR));
    }

    @Test
    void payBillReconciliation_其它实例持有互斥锁_本轮跳过() throws Exception {
        RLock rLock = mock(RLock.class);
        when(serviceLockTool.getLock(any(), any(), any(String[].class))).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(false);

        payBillReconcileTask.payBillReconciliation();

        verify(meterRegistry, org.mockito.Mockito.timeout(3000)).counter(eq(Metrics.PAY_BILL_RECONCILE_TOTAL),
                eq(Metrics.RESULT), eq(Metrics.RESULT_SKIPPED));
        verify(payBillMapper, never()).selectList(any());
    }
}
