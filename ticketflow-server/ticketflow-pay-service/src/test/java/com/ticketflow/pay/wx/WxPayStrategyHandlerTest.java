package com.ticketflow.pay.wx;

import com.ticketflow.entity.PayBill;
import com.ticketflow.pay.wx.config.WxPayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WxPayStrategyHandlerTest {

    private WxPayProperties properties;
    private WxPayStrategyHandler handler;

    @BeforeEach
    void setUp() {
        properties = new WxPayProperties();
        properties.setAppId("wxa9d9651ae0000001");
        properties.setMchId("1900000001");
        // dataVerify 不依赖 SDK 对象，传 null 即可
        handler = new WxPayStrategyHandler(null, null, null, properties);
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
}
