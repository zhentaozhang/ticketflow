package com.ticketflow.pay.wx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付配置。读取 wxpay.* 前缀的配置项。
 * 证书相关参数从微信支付商户平台获取。
 */
@Data
@ConfigurationProperties(prefix = WxPayProperties.PREFIX)
public class WxPayProperties {

    public static final String PREFIX = "wxpay";

    /**
     * 微信支付分配的公众账号ID
     */
    private String appId;

    /**
     * 微信支付商户号
     */
    private String mchId;

    /**
     * 商户API证书序列号
     */
    private String merchantSerialNo;

    /**
     * 商户API私钥文件路径（apiclient_key.pem）
     */
    private String merchantPrivateKeyPath;

    /**
     * 商户APIv3密钥
     */
    private String apiV3Key;
}
