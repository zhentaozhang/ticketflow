package com.ticketflow.pay;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 支付策略上下文。根据支付渠道(支付宝/微信)动态选择支付策略处理器。
 */
public class PayStrategyContext {
    
    private final Map<String,PayStrategyHandler> payStrategyHandlerMap = new HashMap<>();
    
    public void put(String channel,PayStrategyHandler payStrategyHandler){
        payStrategyHandlerMap.put(channel,payStrategyHandler);
    }
    
    public PayStrategyHandler get(String channel){
        return Optional.ofNullable(payStrategyHandlerMap.get(channel)).orElseThrow(
                () -> new TicketFlowFrameException(BaseCode.PAY_STRATEGY_NOT_EXIST));
    }
}
