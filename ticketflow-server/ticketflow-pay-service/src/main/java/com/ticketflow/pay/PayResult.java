package com.ticketflow.pay;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 支付结果。封装支付操作的执行结果。
 */
@Data
@AllArgsConstructor
public class PayResult {
    
    private final boolean success;
    
    private final String body;
}
