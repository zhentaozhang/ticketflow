package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketflow.dto.NotifyDto;
import com.ticketflow.dto.PayBillDto;
import com.ticketflow.dto.PayDto;
import com.ticketflow.dto.RefundDto;
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.dto.RefundStatusDto;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.enums.RefundStatus;
import com.ticketflow.vo.RefundStatusVo;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.mapper.RefundBillMapper;
import com.ticketflow.pay.PayResult;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.pay.TradeResult;
import com.ticketflow.vo.NotifyVo;
import com.ticketflow.vo.PayBillVo;
import com.ticketflow.vo.TradeCheckVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_SUCCESS_RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayServiceTest {

    @Mock
    private PayBillMapper payBillMapper;

    @Mock
    private RefundBillMapper refundBillMapper;

    @Mock
    private PayStrategyContext payStrategyContext;

    @Mock
    private PayStrategyHandler payStrategyHandler;

    @Mock
    private UidGenerator uidGenerator;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private PayService payService;

    @BeforeEach
    void setUp() {
        when(payStrategyContext.get(anyString())).thenReturn(payStrategyHandler);
        when(uidGenerator.getUid()).thenReturn(1000L);
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());
        // TransactionTemplate 在单测里直接执行回调，等价于同步事务
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private PayBill payBill(Integer status) {
        PayBill payBill = new PayBill();
        payBill.setId(1L);
        payBill.setOutOrderNo("20260804000000000001");
        payBill.setPayChannel("alipay");
        payBill.setPayAmount(new BigDecimal("100.00"));
        payBill.setPayBillStatus(status);
        return payBill;
    }

    private Map<String, String> notifyParams() {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "20260804000000000001");
        return params;
    }

    private NotifyDto notifyDto() {
        NotifyDto dto = new NotifyDto();
        dto.setChannel(PayChannel.ALIPAY.getValue());
        dto.setParams(notifyParams());
        return dto;
    }

    // ==================== commonPay ====================

    @Test
    void commonPay_已有账单非未支付_拒绝() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        PayDto payDto = new PayDto();
        payDto.setOrderNumber("20260804000000000001");
        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.commonPay(payDto));
        assertEquals(BaseCode.PAY_BILL_IS_NOT_NO_PAY.getCode(), ex.getCode());
        verify(payStrategyHandler, never()).pay(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void commonPay_首次支付_插入账单() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(payStrategyHandler.pay(any(), any(), any(), any(), any()))
                .thenReturn(new PayResult(true, "code_url"));

        PayDto payDto = new PayDto();
        payDto.setOrderNumber("20260804000000000001");
        payDto.setPrice(new BigDecimal("100.00"));
        payDto.setSubject("演出票");
        payDto.setChannel("alipay");
        payDto.setPayBillType(1);

        assertEquals("code_url", payService.commonPay(payDto));
        verify(payBillMapper).insert(any(PayBill.class));
        verify(payBillMapper, never()).updateById(any(PayBill.class));
    }

    @Test
    void commonPay_重复调起_仅更新支付时间() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.pay(any(), any(), any(), any(), any()))
                .thenReturn(new PayResult(true, "new_url"));

        PayDto payDto = new PayDto();
        payDto.setOrderNumber("20260804000000000001");
        payDto.setChannel("alipay");

        assertEquals("new_url", payService.commonPay(payDto));
        verify(payBillMapper).updateById(any(PayBill.class));
        verify(payBillMapper, never()).insert(any(PayBill.class));
    }

    @Test
    void commonPay_渠道支付失败_不落库() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(payStrategyHandler.pay(any(), any(), any(), any(), any()))
                .thenReturn(new PayResult(false, null));

        PayDto payDto = new PayDto();
        payDto.setOrderNumber("20260804000000000001");
        payDto.setChannel("alipay");

        assertNull(payService.commonPay(payDto));
        verify(payBillMapper, never()).insert(any(PayBill.class));
        verify(payBillMapper, never()).updateById(any(PayBill.class));
    }

    // ==================== notify ====================

    @Test
    void notify_验签失败_返回失败() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(false);

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result.getPayResult());
        verify(payBillMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void notify_账单不存在_返回失败() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result.getPayResult());
    }

    @Test
    void notify_账单已支付_直通成功() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result.getPayResult());
        assertEquals("20260804000000000001", result.getOutTradeNo());
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void notify_账单已取消_直通成功() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.CANCEL.getCode()));

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result.getPayResult());
    }

    @Test
    void notify_账单已退单_直通成功() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.REFUND.getCode()));

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result.getPayResult());
    }

    @Test
    void notify_数据校验失败_返回失败() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.dataVerify(anyMap(), any(PayBill.class))).thenReturn(false);

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result.getPayResult());
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void notify_成功_更新账单为已支付() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.dataVerify(anyMap(), any(PayBill.class))).thenReturn(true);
        when(payBillMapper.update(any(PayBill.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result.getPayResult());
        assertEquals("20260804000000000001", result.getOutTradeNo());
        ArgumentCaptor<PayBill> payBillCaptor = ArgumentCaptor.forClass(PayBill.class);
        verify(payBillMapper).update(payBillCaptor.capture(), any(LambdaUpdateWrapper.class));
        // 成功回调需记录支付成功时间（payTime 语义=支付完成时间，而非发起支付时间）
        assertNotNull(payBillCaptor.getValue().getPayTime());
    }

    @Test
    void notify_更新0行_返回失败() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.dataVerify(anyMap(), any(PayBill.class))).thenReturn(true);
        when(payBillMapper.update(any(PayBill.class), any(LambdaUpdateWrapper.class))).thenReturn(0);

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result.getPayResult());
        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void notify_微信渠道成功_应答SUCCESS() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.dataVerify(anyMap(), any(PayBill.class))).thenReturn(true);
        when(payBillMapper.update(any(PayBill.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        NotifyDto dto = notifyDto();
        dto.setChannel(PayChannel.WX.getValue());
        NotifyVo result = payService.notify(dto);

        assertEquals(WX_NOTIFY_SUCCESS_RESULT, result.getPayResult());
    }

    @Test
    void notify_微信渠道失败_应答FAIL() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(false);

        NotifyDto dto = notifyDto();
        dto.setChannel(PayChannel.WX.getValue());
        NotifyVo result = payService.notify(dto);

        assertEquals(WX_NOTIFY_FAILURE_RESULT, result.getPayResult());
    }

    // ==================== tradeCheck ====================

    private TradeCheckDto tradeCheckDto() {
        TradeCheckDto dto = new TradeCheckDto();
        dto.setOutTradeNo("20260804000000000001");
        dto.setChannel(PayChannel.ALIPAY.getValue());
        return dto;
    }

    private TradeResult tradeResult(boolean success, Integer payBillStatus, BigDecimal totalAmount) {
        TradeResult result = new TradeResult();
        result.setSuccess(success);
        result.setOutTradeNo("20260804000000000001");
        result.setPayBillStatus(payBillStatus);
        result.setTotalAmount(totalAmount);
        return result;
    }

    @Test
    void tradeCheck_渠道查询失败_不更新() {
        when(payStrategyHandler.queryTrade(anyString())).thenReturn(tradeResult(false, null, null));

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

        verify(payBillMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_账单不存在_不更新() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("100.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        PayBill bill = payBill(PayBillStatus.PAY.getCode());

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

        assertEquals(bill.getPayAmount(), result.getTotalAmount());
        assertEquals(false, result.isSuccess());
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_金额不一致_不更新() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("999.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

        assertEquals(false, result.isSuccess());
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_状态一致_不更新() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("100.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

        assertEquals(PayBillStatus.PAY.getCode(), result.getPayBillStatus());
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_状态不一致_以渠道为准更新() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("100.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

        assertEquals(PayBillStatus.PAY.getCode(), result.getPayBillStatus());
        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_已退款终态_渠道返回PAY不回退() {
        // 支付宝退款后 trade_status 仍可能映射为 PAY，终态 REFUND 不允许被回写
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("100.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.REFUND.getCode()));

        payService.tradeCheck(tradeCheckDto());

        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_已取消终态_渠道返回PAY不回退() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("100.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.CANCEL.getCode()));

        payService.tradeCheck(tradeCheckDto());

        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    // ==================== refund ====================

    private RefundDto refundDto() {
        RefundDto dto = new RefundDto();
        dto.setOrderNumber("20260804000000000001");
        dto.setAmount(new BigDecimal("50.00"));
        dto.setChannel(PayChannel.ALIPAY.getValue());
        dto.setReason("取消订单退款");
        return dto;
    }

    @Test
    void refund_账单不存在_拒绝() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.refund(refundDto()));
        assertEquals(BaseCode.PAY_BILL_NOT_EXIST.getCode(), ex.getCode());
    }

    @Test
    void refund_账单非已支付_拒绝() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.refund(refundDto()));
        assertEquals(BaseCode.PAY_BILL_IS_NOT_PAY_STATUS.getCode(), ex.getCode());
    }

    @Test
    void refund_金额超限_拒绝() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("1000.00"));
        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.refund(dto));
        assertEquals(BaseCode.REFUND_AMOUNT_GREATER_THAN_PAY_AMOUNT.getCode(), ex.getCode());
    }

    @Test
    void refund_累计已退超限_拒绝() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        RefundBill existed = new RefundBill();
        existed.setRefundAmount(new BigDecimal("60.00"));
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existed));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("50.00"));
        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.refund(dto));
        assertEquals(BaseCode.REFUND_AMOUNT_GREATER_THAN_PAY_AMOUNT.getCode(), ex.getCode());
        verify(payStrategyHandler, never()).refund(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void refund_在途单未确认不算已确认累计_账单保持已支付() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        // 存量：60 元退款单渠道处理中（refundStatus=1，未确认到账）
        RefundBill processing = new RefundBill();
        processing.setRefundStatus(1);
        processing.setRefundAmount(new BigDecimal("60.00"));
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processing));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("40.00"));
        payService.refund(dto);

        // 累计达全额（60+40=100）但已确认累计（40）未达全额：
        // 在途单最终可能失败，账单保持 PAY 可继续退款，避免少退
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_已确认累计满额_账单置已退款() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        // 存量：60 元退款单已确认到账（refundStatus=2）
        RefundBill confirmed = new RefundBill();
        confirmed.setRefundStatus(2);
        confirmed.setRefundAmount(new BigDecimal("60.00"));
        RefundBill current = new RefundBill();
        current.setRefundStatus(2);
        current.setRefundAmount(new BigDecimal("40.00"));
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(confirmed))
                .thenReturn(List.of(confirmed, current));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("40.00"));
        payService.refund(dto);

        // 已确认累计 60 + 本次成功 40 = 100 达全额，置 REFUND
        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_同幂等键重复_命中已有退款单不重复调用渠道() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        RefundDto dto = refundDto();
        dto.setRefundRequestId("closed-refund:20260804000000000001:50.00");
        RefundBill existed = new RefundBill();
        existed.setOutRefundNo(dto.getRefundRequestId());
        existed.setRefundStatus(2);
        when(refundBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existed);

        String outRefundNo = payService.refund(dto);

        assertEquals("closed-refund:20260804000000000001:50.00", outRefundNo);
        verify(payStrategyHandler, never()).refund(anyString(), any(), any(), anyString(), anyString());
        verify(refundBillMapper, never()).insert(any(RefundBill.class));
    }

    @Test
    void refund_同幂等键重复_此前已失败_允许用同一幂等键重入() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        RefundDto dto = refundDto();
        dto.setRefundRequestId("closed-refund:20260804000000000001:50.00");
        RefundBill existed = new RefundBill();
        existed.setId(2000L);
        existed.setOutRefundNo(dto.getRefundRequestId());
        existed.setRefundStatus(3); // 渠道明确失败终态，钱没退出去 → 可以重入
        when(refundBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existed);
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));

        String outRefundNo = payService.refund(dto);

        assertEquals("closed-refund:20260804000000000001:50.00", outRefundNo);
        // 复用同一退款单（不新建），渠道侧也是同一幂等键，不会退第二次款
        verify(refundBillMapper, never()).insert(any(RefundBill.class));
        verify(payStrategyHandler).refund(anyString(), any(), any(), anyString(), anyString());
        ArgumentCaptor<RefundBill> updateCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper, atLeastOnce()).updateById(updateCaptor.capture());
        List<RefundBill> updates = updateCaptor.getAllValues();
        assertEquals(RefundStatus.REFUND_SUCCESS.getCode(),
                updates.get(updates.size() - 1).getRefundStatus());
    }

    @Test
    void refund_同幂等键重复_受理待确认_只查渠道不重发() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        RefundDto dto = refundDto();
        dto.setRefundRequestId("closed-refund:20260804000000000001:50.00");
        RefundBill existed = new RefundBill();
        existed.setId(2000L);
        existed.setOutRefundNo(dto.getRefundRequestId());
        existed.setRefundStatus(RefundStatus.REFUND_ACCEPT.getCode());
        when(refundBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existed);
        // 渠道这次仍然没给出明确结果
        when(payStrategyHandler.queryRefund(anyString(), anyString()))
                .thenReturn(new RefundResult(false, null, "未知", null));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class, () -> payService.refund(dto));

        assertEquals("退款结果未确认，请稍后重试或等待渠道异步确认", ex.getMessage());
        // 关键：一次都不能重发
        verify(payStrategyHandler, never()).refund(anyString(), any(), any(), anyString(), anyString());
        verify(refundBillMapper, never()).insert(any(RefundBill.class));
        verify(refundBillMapper, never()).updateById(any(RefundBill.class));
    }

    @Test
    void notify_非法渠道_抛策略不存在异常() {
        when(payStrategyContext.get(anyString()))
                .thenThrow(new TicketFlowFrameException(BaseCode.PAY_STRATEGY_NOT_EXIST));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.notify(notifyDto()));
        assertEquals(BaseCode.PAY_STRATEGY_NOT_EXIST.getCode(), ex.getCode());
        verify(payBillMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void refund_部分退款_先落受理单再回填成功状态_账单保持已支付() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));

        String result = payService.refund(refundDto());

        assertEquals("1000", result);
        // 落库时是「受理待确认」（0），渠道给出结果后才回填
        ArgumentCaptor<RefundBill> insertCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper).insert(insertCaptor.capture());
        assertEquals("1000", insertCaptor.getValue().getOutRefundNo());
        assertEquals(new BigDecimal("50.00"), insertCaptor.getValue().getRefundAmount());
        assertEquals(RefundStatus.REFUND_ACCEPT.getCode(), insertCaptor.getValue().getRefundStatus());
        // 顺序保证：钱可能已经退出去，本地必须先有记录
        InOrder inOrder = inOrder(refundBillMapper, payStrategyHandler);
        inOrder.verify(refundBillMapper).insert(any(RefundBill.class));
        inOrder.verify(payStrategyHandler).refund(anyString(), any(), any(), anyString(), anyString());

        ArgumentCaptor<RefundBill> updateCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper).updateById(updateCaptor.capture());
        assertEquals(RefundStatus.REFUND_SUCCESS.getCode(), updateCaptor.getValue().getRefundStatus());
        // 部分退款（50/100）不改变账单状态
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void refund_渠道调用抛异常_退款单保持受理待确认_抛退款异常() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("渠道超时"));

        TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                () -> payService.refund(refundDto()));

        assertEquals(BaseCode.REFUND_ERROR.getCode(), ex.getCode());
        // 受理单已经落库，且没有被改成失败：超时下无法判断钱是否已经退出去了
        verify(refundBillMapper).insert(any(RefundBill.class));
        verify(refundBillMapper, never()).updateById(any(RefundBill.class));
    }

    @Test
    void refund_全额退款_账单置已退款() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));
        // 回填后重新统计「已确认到账」的退款单（DB 里已经包含刚更新的这一笔）
        RefundBill justConfirmed = new RefundBill();
        justConfirmed.setRefundStatus(RefundStatus.REFUND_SUCCESS.getCode());
        justConfirmed.setRefundAmount(new BigDecimal("100.00"));
        // 第一次 selectList 是「校验累计已退金额」（此时还没退过），第二次才是回填后的确认统计
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(justConfirmed));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("100.00"));
        payService.refund(dto);

        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_全额退款渠道处理中_账单保持已支付() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "PROCESSING", "PROCESSING", 1));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("100.00"));
        payService.refund(dto);

        // 处理中不置账单为已退款，待 RefundCheckTask 确认成功后翻转
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_已退部分金额_剩余额度内可继续退() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        // 已有处理中的部分退款 60 元，剩余额度 40 元内可继续退（部分退款场景）
        RefundBill processing = new RefundBill();
        processing.setRefundStatus(1);
        processing.setRefundAmount(new BigDecimal("60.00"));
        when(refundBillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(processing));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS", 2));

        RefundDto dto = refundDto();
        dto.setAmount(new BigDecimal("40.00"));
        payService.refund(dto);

        verify(payStrategyHandler).refund(anyString(), any(), any(), anyString(), anyString());
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_微信处理中_退款单回填处理中() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(true, "PROCESSING", "PROCESSING", 1));

        payService.refund(refundDto());

        ArgumentCaptor<RefundBill> insertCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper).insert(insertCaptor.capture());
        assertEquals(RefundStatus.REFUND_ACCEPT.getCode(), insertCaptor.getValue().getRefundStatus());
        ArgumentCaptor<RefundBill> updateCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper).updateById(updateCaptor.capture());
        assertEquals(RefundStatus.REFUND_PROCESSING.getCode(), updateCaptor.getValue().getRefundStatus());
        // 处理中不置账单为已退款
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void refund_渠道明确失败_受理单落失败终态() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundResult(false, null, "退款失败", null));

        assertThrows(TicketFlowFrameException.class, () -> payService.refund(refundDto()));
        // 受理单先落库（钱可能已经出去了），渠道明确拒绝后才置失败终态
        verify(refundBillMapper).insert(any(RefundBill.class));
        ArgumentCaptor<RefundBill> updateCaptor = ArgumentCaptor.forClass(RefundBill.class);
        verify(refundBillMapper).updateById(updateCaptor.capture());
        assertEquals(RefundStatus.REFUND_FAIL.getCode(), updateCaptor.getValue().getRefundStatus());
    }

    // ==================== refundStatus ====================

    @Test
    void refundStatus_命中_返回退款单状态() {
        RefundBill bill = new RefundBill();
        bill.setOutOrderNo("20260804000000000001");
        bill.setOutRefundNo("1000");
        bill.setRefundStatus(RefundStatus.REFUND_SUCCESS.getCode());
        when(refundBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(bill);

        RefundStatusDto dto = new RefundStatusDto();
        dto.setOutOrderNo("20260804000000000001");
        dto.setOutRefundNo("1000");
        RefundStatusVo vo = payService.refundStatus(dto);

        assertEquals(RefundStatus.REFUND_SUCCESS.getCode(), vo.getRefundStatus());
    }

    @Test
    void refundStatus_查不到退款单_返回空状态() {
        when(refundBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        RefundStatusDto dto = new RefundStatusDto();
        dto.setOutOrderNo("20260804000000000001");
        dto.setOutRefundNo("1000");
        RefundStatusVo vo = payService.refundStatus(dto);

        // 查不到 = 不知道钱退没退，绝不能当作已退款
        assertNull(vo.getRefundStatus());
    }

    // ==================== detail ====================

    @Test
    void detail_命中_返回账单信息() {
        PayBill bill = payBill(PayBillStatus.PAY.getCode());
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(bill);

        PayBillDto dto = new PayBillDto();
        dto.setOrderNumber("20260804000000000001");
        PayBillVo result = payService.detail(dto);

        assertEquals(bill.getOutOrderNo(), result.getOutOrderNo());
        assertEquals(bill.getPayAmount(), result.getPayAmount());
        assertEquals(bill.getPayBillStatus(), result.getPayBillStatus());
    }

    @Test
    void detail_未命中_返回空vo() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        PayBillDto dto = new PayBillDto();
        dto.setOrderNumber("20260804000000000001");
        PayBillVo result = payService.detail(dto);

        assertNull(result.getOutOrderNo());
    }
}
