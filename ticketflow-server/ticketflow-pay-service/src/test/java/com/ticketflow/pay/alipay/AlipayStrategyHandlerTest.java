package com.ticketflow.pay.alipay;

import com.ticketflow.entity.PayBill;
import com.ticketflow.pay.alipay.config.AlipayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlipayStrategyHandlerTest {

    private AlipayStrategyHandler handler;

    @BeforeEach
    void setUp() {
        AlipayProperties properties = new AlipayProperties();
        properties.setAppId("2021000000000000");
        properties.setSellerId("2088000000000000");
        // dataVerify 依赖 AlipayClient 参与验签/网络请求，传 null 即可
        handler = new AlipayStrategyHandler(null, properties);
    }

    private PayBill payBill(String amount) {
        PayBill payBill = new PayBill();
        payBill.setPayAmount(new BigDecimal(amount));
        return payBill;
    }

    private Map<String, String> alipayParams(String totalAmount, String sellerId, String appId, String tradeStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("total_amount", totalAmount);
        params.put("seller_id", sellerId);
        params.put("app_id", appId);
        params.put("trade_status", tradeStatus);
        return params;
    }

    @Test
    void dataVerify_金额一致_通过() {
        assertTrue(handler.dataVerify(alipayParams("1.00", "2088000000000000", "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_金额不一致_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("2.00", "2088000000000000", "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_缺少金额_拒绝() {
        assertFalse(handler.dataVerify(alipayParams(null, "2088000000000000", "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_金额格式错误_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("abc", "2088000000000000", "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_sellerId不一致_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", "attacker-seller", "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_缺少sellerId_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", null, "2021000000000000", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_appId不一致_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", "2088000000000000", "attacker-appid", "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_缺少appId_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", "2088000000000000", null, "TRADE_SUCCESS"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_交易状态非TRADE_SUCCESS_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", "2088000000000000", "2021000000000000", "TRADE_FINISHED"),
                payBill("1.00")));
    }

    @Test
    void dataVerify_缺少交易状态_拒绝() {
        assertFalse(handler.dataVerify(alipayParams("1.00", "2088000000000000", "2021000000000000", null),
                payBill("1.00")));
    }
}