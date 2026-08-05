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
import com.ticketflow.enums.PayChannel;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_SUCCESS_RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMocks
    private PayService payService;

    @BeforeEach
    void setUp() {
        when(payStrategyContext.get(anyString())).thenReturn(payStrategyHandler);
        when(uidGenerator.getUid()).thenReturn(1000L);
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

        NotifyVo result = payService.notify(notifyDto());

        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result.getPayResult());
        assertEquals("20260804000000000001", result.getOutTradeNo());
        verify(payBillMapper).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void notify_微信渠道成功_应答SUCCESS() {
        when(payStrategyHandler.signVerify(anyMap())).thenReturn(true);
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.NO_PAY.getCode()));
        when(payStrategyHandler.dataVerify(anyMap(), any(PayBill.class))).thenReturn(true);

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
        verify(payBillMapper, never()).update(any(PayBill.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void tradeCheck_金额不一致_不更新() {
        when(payStrategyHandler.queryTrade(anyString()))
                .thenReturn(tradeResult(true, PayBillStatus.PAY.getCode(), new BigDecimal("999.00")));
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));

        TradeCheckVo result = payService.tradeCheck(tradeCheckDto());

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
    void refund_成功_更新账单并插入退款记录() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), anyString()))
                .thenReturn(new RefundResult(true, "refunded", "SUCCESS"));

        String result = payService.refund(refundDto());

        assertEquals("20260804000000000001", result);
        verify(payBillMapper).updateById(any(PayBill.class));
        verify(refundBillMapper).insert(any(RefundBill.class));
    }

    @Test
    void refund_渠道失败_抛异常() {
        when(payBillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(payBill(PayBillStatus.PAY.getCode()));
        when(payStrategyHandler.refund(anyString(), any(), anyString()))
                .thenReturn(new RefundResult(false, null, "退款失败"));

        assertThrows(TicketFlowFrameException.class, () -> payService.refund(refundDto()));
        verify(refundBillMapper, never()).insert(any(RefundBill.class));
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