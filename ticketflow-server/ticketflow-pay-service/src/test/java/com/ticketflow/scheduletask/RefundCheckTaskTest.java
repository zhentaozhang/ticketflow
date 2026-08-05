package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.mapper.RefundBillMapper;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundCheckTaskTest {

    @Mock
    private RefundBillMapper refundBillMapper;

    @Mock
    private PayBillMapper payBillMapper;

    @Mock
    private PayStrategyContext payStrategyContext;

    @Mock
    private PayStrategyHandler payStrategyHandler;

    @InjectMocks
    private RefundCheckTask refundCheckTask;

    @BeforeEach
    void setUp() {
        when(payStrategyContext.get(anyString())).thenReturn(payStrategyHandler);
    }

    private RefundBill processingBill() {
        RefundBill bill = new RefundBill();
        bill.setId(1L);
        bill.setPayBillId(100L);
        bill.setOutOrderNo("20260804000000000001");
        bill.setOutRefundNo("1000");
        bill.setRefundAmount(new BigDecimal("100.00"));
        bill.setRefundStatus(1);
        return bill;
    }

    private PayBill payBill() {
        PayBill payBill = new PayBill();
        payBill.setId(100L);
        payBill.setOutOrderNo("20260804000000000001");
        payBill.setPayChannel(PayChannel.WX.getValue());
        payBill.setPayAmount(new BigDecimal("100.00"));
        payBill.setPayBillStatus(PayBillStatus.PAY.getCode());
        return payBill;
    }

    @Test
    void 无处理中退款单_不查渠道() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        refundCheckTask.processPendingRefunds();

        verify(payStrategyHandler, never()).queryRefund(anyString(), anyString());
    }

    @Test
    void 渠道确认成功且累计达标_翻转账单为已退款() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processingBill()));
        when(payBillMapper.selectById(100L)).thenReturn(payBill());
        when(payStrategyHandler.queryRefund(anyString(), anyString()))
                .thenReturn(new RefundResult(true, "SUCCESS", "SUCCESS", 2));

        refundCheckTask.processPendingRefunds();

        verify(refundBillMapper).updateById(any(RefundBill.class));
        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void 渠道确认成功但累计不足_不翻转账单() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processingBill()));
        when(payBillMapper.selectById(100L)).thenReturn(payBill());
        when(payStrategyHandler.queryRefund(anyString(), anyString()))
                .thenReturn(new RefundResult(true, "SUCCESS", "SUCCESS", 2));
        // 累计查询（处理中+已成功）返回部分金额：本次成功 60 单，但累计仅 60 < 100
        when(payBillMapper.selectById(100L)).thenReturn(payBill());
        RefundBill successPart = new RefundBill();
        successPart.setRefundAmount(new BigDecimal("60.00"));
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(processingBill()))
                .thenReturn(List.of(successPart));

        refundCheckTask.processPendingRefunds();

        verify(refundBillMapper).updateById(any(RefundBill.class));
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void 渠道仍在处理中_不更新退款单() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processingBill()));
        when(payBillMapper.selectById(100L)).thenReturn(payBill());
        when(payStrategyHandler.queryRefund(anyString(), anyString()))
                .thenReturn(new RefundResult(true, "PROCESSING", "PROCESSING", 1));

        refundCheckTask.processPendingRefunds();

        verify(refundBillMapper, never()).updateById(any(RefundBill.class));
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void 渠道退款失败_置终态不再轮询() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processingBill()));
        when(payBillMapper.selectById(100L)).thenReturn(payBill());
        when(payStrategyHandler.queryRefund(anyString(), anyString()))
                .thenReturn(new RefundResult(false, "CLOSED", "CLOSED", null));

        refundCheckTask.processPendingRefunds();

        verify(refundBillMapper).updateById(any(RefundBill.class));
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void 账单不存在_跳过该退款单() {
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processingBill()));
        when(payBillMapper.selectById(100L)).thenReturn(null);

        refundCheckTask.processPendingRefunds();

        verify(payStrategyHandler, never()).queryRefund(anyString(), anyString());
    }
}
