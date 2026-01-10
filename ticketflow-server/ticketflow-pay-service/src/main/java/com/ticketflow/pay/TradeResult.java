package com.ticketflow.pay;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 交易状态查询结果。封装向支付平台查询的交易状态数据。
 */
@Data
public class TradeResult {
    
    private boolean success;
    
    private Integer payBillStatus;
    
    private String outTradeNo;
    
    private BigDecimal totalAmount;
}
