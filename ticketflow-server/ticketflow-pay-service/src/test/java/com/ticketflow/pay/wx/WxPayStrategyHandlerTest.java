package com.ticketflow.pay.wx;

import com.ticketflow.entity.PayBill;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.pay.wx.config.WxPayProperties;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.ticketflow.constant.Constant.WX_NONCE_HEADER;
import static com.ticketflow.constant.Constant.WX_RAW_BODY_KEY;
import static com.ticketflow.constant.Constant.WX_SERIAL_HEADER;
import static com.ticketflow.constant.Constant.WX_SIGNATURE_HEADER;
import static com.ticketflow.constant.Constant.WX_TIMESTAMP_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxPayStrategyHandlerTest {

    private WxPayProperties properties;
    private NotificationParser notificationParser;
    private WxPayStrategyHandler handler;

    @BeforeEach
    void setUp() {
        properties = new WxPayProperties();
        properties.setAppId("wxa9d9651ae0000001");
        properties.setMchId("1900000001");
        // dataVerify / 重放防护不依赖 SDK 对象，仅 signVerify 需要 mock parser
        notificationParser = mock(NotificationParser.class);
        handler = new WxPayStrategyHandler(null, notificationParser, null, properties);
    }

    private PayBill payBill(String amount) {
        PayBill payBill = new PayBill();
        payBill.setPayAmount(new BigDecimal(amount));
        return payBill;
    }

    private Map<String, String> wxParams(String totalFeeFen, String appid, String mchid, String tradeState) {
        Map<String, String> params = new HashMap<>();
        params.put("total_fee", totalFeeFen);
        params.put("appid", appid);
        params.put("mchid", mchid);
        params.put("trade_state", tradeState);
        return params;
    }

    private Transaction transaction() {
        Transaction transaction = new Transaction();
        transaction.setOutTradeNo("202608041234567890");
        transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        TransactionAmount amount = new TransactionAmount();
        amount.setTotal(100);
        transaction.setAmount(amount);
        transaction.setAppid("wxa9d9651ae0000001");
        transaction.setMchid("1900000001");
        return transaction;
    }

    /**
     * 构造一次合法回调的 params（当前时间戳 + 唯一 nonce）。
     * signVerify 前置于时间戳/去重校验，用例间 nonce 必须不同（static 缓存共享）。
     */
    private Map<String, String> signParams(String nonce) {
        Map<String, String> params = new HashMap<>();
        params.put(WX_RAW_BODY_KEY, "{\"raw\":\"body\"}");
        params.put(WX_SIGNATURE_HEADER, "fake-signature");
        params.put(WX_SERIAL_HEADER, "fake-serial");
        params.put(WX_NONCE_HEADER, nonce);
        params.put(WX_TIMESTAMP_HEADER, String.valueOf(System.currentTimeMillis() / 1000));
        return params;
    }

    private String uniqueNonce() {
        return "nonce-" + System.nanoTime();
    }

    @Test
    void dataVerify_金额一致_通过() {
        assertTrue(handler.dataVerify(wxParams("100", "wxa9d9651ae0000001", "1900000001", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_金额不一致_拒绝() {
        assertFalse(handler.dataVerify(wxParams("200", "wxa9d9651ae0000001", "1900000001", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_appid不一致_拒绝() {
        assertFalse(handler.dataVerify(wxParams("100", "attacker-appid", "1900000001", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_mchid不一致_拒绝() {
        assertFalse(handler.dataVerify(wxParams("100", "wxa9d9651ae0000001", "attacker-mchid", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_交易状态非SUCCESS_拒绝() {
        assertFalse(handler.dataVerify(wxParams("100", "wxa9d9651ae0000001", "1900000001", "NOTPAY"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_缺少金额_拒绝() {
        assertFalse(handler.dataVerify(wxParams(null, "wxa9d9651ae0000001", "1900000001", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_金额格式错误_拒绝() {
        assertFalse(handler.dataVerify(wxParams("abc", "wxa9d9651ae0000001", "1900000001", "SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void signVerify_验签成功_回写params() throws Exception {
        String nonce = uniqueNonce();
        when(notificationParser.parse(any(RequestParam.class), eq(Transaction.class))).thenReturn(transaction());

        Map<String, String> params = signParams(nonce);
        assertTrue(handler.signVerify(params));

        assertEquals("202608041234567890", params.get("out_trade_no"));
        assertEquals("SUCCESS", params.get("trade_state"));
        assertEquals("100", params.get("total_fee"));
        assertEquals("wxa9d9651ae0000001", params.get("appid"));
        assertEquals("1900000001", params.get("mchid"));

        ArgumentCaptor<RequestParam> captor = ArgumentCaptor.forClass(RequestParam.class);
        verify(notificationParser).parse(captor.capture(), eq(Transaction.class));
        RequestParam requestParam = captor.getValue();
        assertEquals("fake-serial", requestParam.getSerialNumber());
        assertEquals("fake-signature", requestParam.getSignature());
        assertEquals("{\"raw\":\"body\"}", requestParam.getBody());
    }

    @Test
    void signVerify_验签失败_返回false() throws Exception {
        when(notificationParser.parse(any(RequestParam.class), eq(Transaction.class)))
                .thenThrow(new RuntimeException("invalid signature"));

        assertFalse(handler.signVerify(signParams(uniqueNonce())));
    }

    @Test
    void signVerify_时间戳过期_拒绝() {
        Map<String, String> params = signParams(uniqueNonce());
        params.put(WX_TIMESTAMP_HEADER, "0");

        assertFalse(handler.signVerify(params));
        // 验签未执行
        assertNull(params.get("out_trade_no"));
    }

    @Test
    void signVerify_缺少时间戳_拒绝() {
        Map<String, String> params = signParams(uniqueNonce());
        params.remove(WX_TIMESTAMP_HEADER);

        assertFalse(handler.signVerify(params));
    }

    @Test
    void signVerify_缺少nonce_拒绝() {
        Map<String, String> params = signParams(null);

        assertFalse(handler.signVerify(params));
        verify(notificationParser, never()).parse(any(), any());
    }

    @Test
    void signVerify_nonce重复_拒绝() throws Exception {
        String nonce = uniqueNonce();
        when(notificationParser.parse(any(RequestParam.class), eq(Transaction.class))).thenReturn(transaction());

        assertTrue(handler.signVerify(signParams(nonce)));
        // 同一 nonce 再次到达视为重放
        assertFalse(handler.signVerify(signParams(nonce)));
        verify(notificationParser).parse(any(RequestParam.class), eq(Transaction.class));
    }

    // ==================== refund / queryRefund ====================

    @Test
    void refund_部分退款_total为原支付金额_refund为退款金额() throws Exception {
        RefundService refundService = mock(RefundService.class);
        WxPayStrategyHandler refundHandler = new WxPayStrategyHandler(null, notificationParser, refundService, properties);
        Refund refund = new Refund();
        refund.setStatus(Status.SUCCESS);
        when(refundService.create(any(CreateRequest.class))).thenReturn(refund);

        RefundResult result = refundHandler.refund("202608041234567890",
                new BigDecimal("40.00"), new BigDecimal("100.00"), "部分退款", "refund-123");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getRefundStatus());
        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(refundService).create(captor.capture());
        CreateRequest request = captor.getValue();
        assertEquals("refund-123", request.getOutRefundNo());
        // total=原支付金额 100 元=10000 分，refund=本次退款 40 元=4000 分
        assertEquals(10000L, request.getAmount().getTotal());
        assertEquals(4000L, request.getAmount().getRefund());
    }

    @Test
    void refund_微信处理中_返回处理中状态() throws Exception {
        RefundService refundService = mock(RefundService.class);
        WxPayStrategyHandler refundHandler = new WxPayStrategyHandler(null, notificationParser, refundService, properties);
        Refund refund = new Refund();
        refund.setStatus(Status.PROCESSING);
        when(refundService.create(any(CreateRequest.class))).thenReturn(refund);

        RefundResult result = refundHandler.refund("202608041234567890",
                new BigDecimal("100.00"), new BigDecimal("100.00"), "全额退款", "refund-456");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRefundStatus());
    }

    @Test
    void refund_退款关闭_返回失败() throws Exception {
        RefundService refundService = mock(RefundService.class);
        WxPayStrategyHandler refundHandler = new WxPayStrategyHandler(null, notificationParser, refundService, properties);
        Refund refund = new Refund();
        refund.setStatus(Status.CLOSED);
        when(refundService.create(any(CreateRequest.class))).thenReturn(refund);

        RefundResult result = refundHandler.refund("202608041234567890",
                new BigDecimal("100.00"), new BigDecimal("100.00"), "全额退款", "refund-789");

        assertFalse(result.isSuccess());
    }

    @Test
    void queryRefund_成功_返回已退款() throws Exception {
        RefundService refundService = mock(RefundService.class);
        WxPayStrategyHandler refundHandler = new WxPayStrategyHandler(null, notificationParser, refundService, properties);
        Refund refund = new Refund();
        refund.setStatus(Status.SUCCESS);
        when(refundService.queryByOutRefundNo(any(QueryByOutRefundNoRequest.class))).thenReturn(refund);

        RefundResult result = refundHandler.queryRefund("202608041234567890", "refund-123");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getRefundStatus());
        ArgumentCaptor<QueryByOutRefundNoRequest> captor =
                ArgumentCaptor.forClass(QueryByOutRefundNoRequest.class);
        verify(refundService).queryByOutRefundNo(captor.capture());
        assertEquals("refund-123", captor.getValue().getOutRefundNo());
    }

    @Test
    void queryRefund_处理中_返回处理中状态() throws Exception {
        RefundService refundService = mock(RefundService.class);
        WxPayStrategyHandler refundHandler = new WxPayStrategyHandler(null, notificationParser, refundService, properties);
        Refund refund = new Refund();
        refund.setStatus(Status.PROCESSING);
        when(refundService.queryByOutRefundNo(any(QueryByOutRefundNoRequest.class))).thenReturn(refund);

        RefundResult result = refundHandler.queryRefund("202608041234567890", "refund-123");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRefundStatus());
    }
}
