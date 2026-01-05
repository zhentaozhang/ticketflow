package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.NotifyDto;
import com.ticketflow.dto.PayDto;
import com.ticketflow.dto.RefundDto;
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.NotifyVo;
import com.ticketflow.vo.TradeCheckVo;
import org.springframework.stereotype.Component;

/**
 * 支付服务Feign降级。支付服务不可用时的降级处理。
 */
@Component
public class PayClientFallback implements PayClient{
    
    @Override
    public ApiResponse<String> commonPay(final PayDto payDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 支付发起失败 → 用户无法跳转支付
    }
    
    @Override
    public ApiResponse<NotifyVo> notify(final NotifyDto notifyDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 回调失败 → 依赖补偿或对账
    }
    
    @Override
    public ApiResponse<TradeCheckVo> tradeCheck(final TradeCheckDto tradeCheckDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<String> refund(final RefundDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
