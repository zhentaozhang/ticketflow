package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.NotifyDto;
import com.ticketflow.dto.PayDto;
import com.ticketflow.dto.RefundDto;
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.vo.NotifyVo;
import com.ticketflow.vo.TradeCheckVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 支付服务 Feign 客户端。
 * order-service 通过此接口发起支付、查询支付状态、退款和接收回调
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "pay-service", fallback = PayClientFallback.class)
//                ↑ Nacos 服务名  ↑ order-service 支付流程调用
public interface PayClient {
    /**
     * 发起支付（返回支付链接/二维码ID）
     * order-service 创建订单后调用
     */
    @PostMapping(value = "/pay/common/pay")
    ApiResponse<String> commonPay(PayDto dto);

    /**
     * 支付回调通知（支付成功后更新订单状态）
     */
    @PostMapping(value = "/pay/notify")
    ApiResponse<NotifyVo> notify(NotifyDto dto);

    /**
     * 查询支付结果（主动轮询）
     */
    @PostMapping(value = "/pay/trade/check")
    ApiResponse<TradeCheckVo> tradeCheck(TradeCheckDto dto);

    /**
     * 退款
     */
    @PostMapping(value = "/pay/refund")
    ApiResponse<String> refund(RefundDto dto);
}
