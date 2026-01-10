package com.ticketflow.pay;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 退款结果。封装退款操作的执行结果。
 */
@Data
@AllArgsConstructor
public class RefundResult {
    
    private final boolean success;
    
    private final String body;
    
    private final String message;
}
