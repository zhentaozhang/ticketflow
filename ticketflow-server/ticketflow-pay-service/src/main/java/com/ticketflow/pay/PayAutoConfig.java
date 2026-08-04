package com.ticketflow.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.AlipayConstants;
import com.alipay.api.DefaultAlipayClient;
import com.ticketflow.pay.alipay.AlipayStrategyHandler;
import com.ticketflow.pay.alipay.config.AlipayProperties;
import com.ticketflow.pay.wx.WxPayStrategyHandler;
import com.ticketflow.pay.wx.config.WxPayProperties;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.refund.RefundService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 支付自动配置。初始化支付宝客户端和支付策略处理器，自动配置支付相关Bean。
 */

@EnableConfigurationProperties({AlipayProperties.class, WxPayProperties.class})
public class PayAutoConfig {
    
    @Bean
    public AlipayClient alipayClient(AlipayProperties aliPayProperties) throws AlipayApiException {
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl(aliPayProperties.getGatewayUrl());
        alipayConfig.setAppId(aliPayProperties.getAppId());
        alipayConfig.setPrivateKey(aliPayProperties.getMerchantPrivateKey());
        alipayConfig.setFormat(AlipayConstants.FORMAT_JSON);
        alipayConfig.setCharset(AlipayConstants.CHARSET_UTF8);
        alipayConfig.setAlipayPublicKey(aliPayProperties.getAlipayPublicKey());
        alipayConfig.setSignType(AlipayConstants.SIGN_TYPE_RSA2);
        //构造client
        return new DefaultAlipayClient(alipayConfig);
    }
    
    @Bean
    public PayStrategyContext payStrategyContext(){
        return new PayStrategyContext();
    }
    
    @Bean
    public PayStrategyInitHandler payStrategyInitHandler(PayStrategyContext payStrategyContext){
        return new PayStrategyInitHandler(payStrategyContext);
    }
    
    @Bean
    public AlipayStrategyHandler alipayCall(AlipayClient alipayClient, AlipayProperties aliPayProperties){
        return new AlipayStrategyHandler(alipayClient,aliPayProperties);
    }

    /**
     * 微信支付配置。RSAAutoCertificateConfig 自动下载并每 60 分钟轮换平台证书，
     * 同时实现 Config（请求签名）与 NotificationConfig（回调验签）两个接口。
     */
    @Bean
    public Config wxPayConfig(WxPayProperties wxPayProperties) throws Exception {
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(wxPayProperties.getMchId())
                .privateKeyFromPath(wxPayProperties.getMerchantPrivateKeyPath())
                .merchantSerialNumber(wxPayProperties.getMerchantSerialNo())
                .apiV3Key(wxPayProperties.getApiV3Key())
                .build();
    }

    @Bean
    public NativePayService nativePayService(Config wxPayConfig) {
        return new NativePayService.Builder().config(wxPayConfig).build();
    }

    @Bean
    public RefundService refundService(Config wxPayConfig) {
        return new RefundService.Builder().config(wxPayConfig).build();
    }

    @Bean
    public NotificationParser notificationParser(Config wxPayConfig) {
        return new NotificationParser((NotificationConfig) wxPayConfig);
    }

    @Bean
    public WxPayStrategyHandler wxCall(NativePayService nativePayService, NotificationParser notificationParser,
                                       RefundService refundService, WxPayProperties wxPayProperties) {
        return new WxPayStrategyHandler(nativePayService, notificationParser, refundService, wxPayProperties);
    }
}
