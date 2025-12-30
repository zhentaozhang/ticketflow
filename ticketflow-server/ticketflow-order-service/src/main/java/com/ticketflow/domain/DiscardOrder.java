package com.ticketflow.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 废弃订单。在订单创建过程中因异常（MQ延迟、余票不足等）需要丢弃的订单信息。
 */
@Data
@NoArgsConstructor
public class DiscardOrder {
    /**
     * 参数信息
     * */
    private OrderCreateMq orderCreateMq;
    
    /**
     * 原因
     * */
    private Integer discardOrderReason;
    
    /**
     * 错误信息
     * */
    private String errorMsg;
    
    public DiscardOrder(OrderCreateMq orderCreateMq, Integer discardOrderReason) {
        this.orderCreateMq = orderCreateMq;
        this.discardOrderReason = discardOrderReason;
    }
    
    public DiscardOrder(OrderCreateMq orderCreateMq, Integer discardOrderReason, String errorMsg) {
        this.orderCreateMq = orderCreateMq;
        this.discardOrderReason = discardOrderReason;
        this.errorMsg = errorMsg;
    }
}
