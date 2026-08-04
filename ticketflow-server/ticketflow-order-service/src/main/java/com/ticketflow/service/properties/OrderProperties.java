package com.ticketflow.service.properties;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 订单支付回调地址配置。
 * 支付宝异步通知 URL，通过占位符注入当前服务前缀
 */
@Data
@Component
public class OrderProperties {

    /**
     * 支付成功后通知接口地址
     * */
    @Value("${orderPayNotifyUrl:http://localhost:6085/ticketflow/order/order/alipay/notify}")
    private String orderPayNotifyUrl;

    /**
     * 微信支付成功后通知接口地址
     * */
    @Value("${wxPayNotifyUrl:http://localhost:6085/ticketflow/order/order/wx/notify}")
    private String wxPayNotifyUrl;

    /**
     * 支付成功后跳转页面
     * */
    @Value("${orderPayReturnUrl:http://localhost:5173/order/paySuccess}")
    private String orderPayReturnUrl;
}
