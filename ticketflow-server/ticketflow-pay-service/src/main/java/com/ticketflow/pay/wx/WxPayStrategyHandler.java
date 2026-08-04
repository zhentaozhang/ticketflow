package com.ticketflow.pay.wx;

import com.ticketflow.entity.PayBill;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.pay.PayResult;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.pay.TradeResult;
import com.ticketflow.pay.wx.config.WxPayProperties;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

import static com.ticketflow.constant.Constant.WX_NONCE_HEADER;
import static com.ticketflow.constant.Constant.WX_RAW_BODY_KEY;
import static com.ticketflow.constant.Constant.WX_SERIAL_HEADER;
import static com.ticketflow.constant.Constant.WX_SIGNATURE_HEADER;
import static com.ticketflow.constant.Constant.WX_TIMESTAMP_HEADER;

/**
 * 微信支付策略实现——对接微信支付 APIv3 Native 扫码支付。
 *
 * 回调验签与支付宝不同：微信签名在请求头（Wechatpay-*），业务数据在 AES-GCM
 * 密文里。order-service 入口把原始 body 和 4 个请求头放进 params（特殊 key，
 * 定义在 Constant，跨服务共用），signVerify 内完成验签+解密，并把
 * out_trade_no/trade_state/total_fee/appid/mchid 回写进 params，
 * 供 PayService.notify 后续步骤与 dataVerify 使用。
 */
@Slf4j
@AllArgsConstructor
public class WxPayStrategyHandler implements PayStrategyHandler {

    private static final BigDecimal HUNDRED = new BigDecimal(100);

    private final NativePayService nativePayService;

    private final NotificationParser notificationParser;

    private final RefundService refundService;

    private final WxPayProperties wxPayProperties;

    @Override
    public PayResult pay(String outTradeNo, BigDecimal price, String subject, String notifyUrl, String returnUrl) {
        try {
            PrepayRequest request = new PrepayRequest();
            Amount amount = new Amount();
            // 微信金额单位为分
            amount.setTotal(price.multiply(HUNDRED).intValue());
            amount.setCurrency("CNY");
            request.setAmount(amount);
            request.setAppid(wxPayProperties.getAppId());
            request.setMchid(wxPayProperties.getMchId());
            request.setDescription(subject);
            request.setNotifyUrl(notifyUrl);
            request.setOutTradeNo(outTradeNo);
            PrepayResponse response = nativePayService.prepay(request);
            // Native 支付返回 code_url，前端渲染二维码
            return new PayResult(true, response.getCodeUrl());
        } catch (Exception e) {
            log.error("wx pay error", e);
            throw new TicketFlowFrameException(BaseCode.PAY_ERROR);
        }
    }

    @Override
    public boolean signVerify(Map<String, String> params) {
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(params.get(WX_SERIAL_HEADER))
                    .nonce(params.get(WX_NONCE_HEADER))
                    .signature(params.get(WX_SIGNATURE_HEADER))
                    .timestamp(params.get(WX_TIMESTAMP_HEADER))
                    .body(params.get(WX_RAW_BODY_KEY))
                    .build();
            // 验签 + AES-GCM 解密，得到明文交易信息
            Transaction transaction = notificationParser.parse(requestParam, Transaction.class);
            // 回写进 params，供 PayService.notify 查账单 / dataVerify 使用
            params.put("out_trade_no", transaction.getOutTradeNo());
            params.put("trade_state", transaction.getTradeState().name());
            params.put("total_fee", String.valueOf(transaction.getAmount().getTotal()));
            params.put("appid", transaction.getAppid());
            params.put("mchid", transaction.getMchid());
            return true;
        } catch (Exception e) {
            log.error("wx pay sign verify error", e);
            return false;
        }
    }

    @Override
    public boolean dataVerify(Map<String, String> params, PayBill payBill) {
        // 微信回调金额单位为分，换算回元与本地账单比对
        BigDecimal notifyPayAmount = new BigDecimal(params.get("total_fee")).divide(HUNDRED);
        if (notifyPayAmount.compareTo(payBill.getPayAmount()) != 0) {
            log.error("回调金额和账单支付金额不一致 回调金额 : {}, 账单支付金额 : {}", notifyPayAmount, payBill.getPayAmount());
            return false;
        }
        if (!wxPayProperties.getAppId().equals(params.get("appid"))) {
            log.error("回调appId和已配置appId不一致 回调appId : {}, 已配置appId : {}", params.get("appid"), wxPayProperties.getAppId());
            return false;
        }
        if (!wxPayProperties.getMchId().equals(params.get("mchid"))) {
            log.error("回调mchid和已配置mchid不一致 回调mchid : {}, 已配置mchid : {}", params.get("mchid"), wxPayProperties.getMchId());
            return false;
        }
        // 只有 trade_state 为 SUCCESS 才算支付成功
        if (!"SUCCESS".equals(params.get("trade_state"))) {
            log.error("支付未成功 tradeState : {}", params.get("trade_state"));
            return false;
        }
        return true;
    }

    @Override
    public TradeResult queryTrade(String outTradeNo) {
        TradeResult tradeResult = new TradeResult();
        tradeResult.setSuccess(false);
        try {
            QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
            request.setMchid(wxPayProperties.getMchId());
            request.setOutTradeNo(outTradeNo);
            Transaction transaction = nativePayService.queryOrderByOutTradeNo(request);
            tradeResult.setSuccess(true);
            tradeResult.setOutTradeNo(transaction.getOutTradeNo());
            // 分 → 元
            tradeResult.setTotalAmount(new BigDecimal(transaction.getAmount().getTotal()).divide(HUNDRED));
            tradeResult.setPayBillStatus(convertPayBillStatus(transaction.getTradeState()));
        } catch (Exception e) {
            log.error("wx trade query error, outTradeNo : {}", outTradeNo, e);
        }
        return tradeResult;
    }

    @Override
    public RefundResult refund(String outTradeNo, BigDecimal price, String reason) {
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(outTradeNo);
        // 微信退款单号要求唯一，使用订单号+时间戳
        request.setOutRefundNo(outTradeNo + "-" + System.currentTimeMillis());
        request.setReason(reason);
        com.wechat.pay.java.service.refund.model.AmountReq amount =
                new com.wechat.pay.java.service.refund.model.AmountReq();
        amount.setTotal(price.multiply(HUNDRED).longValue());
        amount.setRefund(price.multiply(HUNDRED).longValue());
        amount.setCurrency("CNY");
        request.setAmount(amount);
        try {
            Refund response = refundService.create(request);
            return new RefundResult(true, response.getStatus().name(), response.getStatus().name());
        } catch (Exception e) {
            log.error("wx refund error", e);
            throw new TicketFlowFrameException(BaseCode.REFUND_ERROR);
        }
    }

    @Override
    public String getChannel() {
        return PayChannel.WX.getValue();
    }

    /**
     * 微信 trade_state → 本地账单状态。
     * 未知状态不抛异常，返回 NO_PAY 并记日志（支付宝此处是抛异常，微信枚举更多，不做同样处理）。
     */
    private Integer convertPayBillStatus(Transaction.TradeStateEnum tradeState) {
        switch (tradeState) {
            case SUCCESS:
                return PayBillStatus.PAY.getCode();
            case NOTPAY:
            case USERPAYING:
            case PAYERROR:
                return PayBillStatus.NO_PAY.getCode();
            case CLOSED:
            case REVOKED:
                return PayBillStatus.CANCEL.getCode();
            case REFUND:
                return PayBillStatus.REFUND.getCode();
            default:
                log.error("未知微信交易状态 tradeState : {}", tradeState);
                return PayBillStatus.NO_PAY.getCode();
        }
    }
}
