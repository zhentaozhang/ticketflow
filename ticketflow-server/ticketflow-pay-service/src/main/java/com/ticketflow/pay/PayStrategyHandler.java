package com.ticketflow.pay;

import com.ticketflow.entity.PayBill;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付方式策略接口——策略模式 + 模板方法。
 *
 * 子类实现 pay() 调用第三方支付网关，
 * 实现 refund() 处理退款，实现 tradeCheck() 查询交易状态。
 * PayStrategyContext 持有当前选择的策略，由 PayService 统一调用
 */
public interface PayStrategyHandler {
    /**
     * 支付
     * @param outTradeNo 订单号
     * @param price 支付价格
     * @param subject 标题
     * @param notifyUrl 回调地址
     * @param returnUrl 支付后返回地址
     * @return 结果
     * */
    PayResult pay(String outTradeNo, BigDecimal price, String subject, String notifyUrl, String returnUrl);
    
    /**
     * 验签
     * @param params 参数
     * @return 结果
     * */
    boolean signVerify(Map<String, String> params);
    
    /**
     * 数据验证
     * @param params 参数
     * @param payBill 支付账单
     * @return 结果
     * */
    boolean dataVerify(Map<String, String> params, PayBill payBill);
    
    /**
     * 状态查询
     * @param outTradeNo 订单号
     * @return 结果
     * */
    TradeResult queryTrade(String outTradeNo);
    
    /**
     * 退款
     * @param outTradeNo 订单号
     * @param price 退款金额
     * @param originalAmount 原支付金额（部分退款时渠道需校验原订单金额）
     * @param reason 原因
     * @param outRefundNo 退款单号（渠道侧幂等键，重复请求返回原结果）
     * @return 结果
     * */
    RefundResult refund(String outTradeNo, BigDecimal price, BigDecimal originalAmount, String reason, String outRefundNo);
    
    /**
     * 退款状态查询
     * @param outTradeNo 订单号
     * @param outRefundNo 退款单号
     * @return 结果（refundStatus 1=处理中 2=已退款成功；success=false 表示退款失败/关闭）
     * */
    RefundResult queryRefund(String outTradeNo, String outRefundNo);
    
    /**
     * 支付渠道
     * @return 结果
     * */
    String getChannel();
}
