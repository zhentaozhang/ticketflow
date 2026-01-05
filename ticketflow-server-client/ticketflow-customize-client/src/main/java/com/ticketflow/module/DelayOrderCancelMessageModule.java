package com.ticketflow.module;

import lombok.Data;

/**
 * DelayOrderCancelMessageModule
 */
@Data
public class DelayOrderCancelMessageModule {

    private Long messageTraceId;
    
    private Long messageId;
    
    private Long programId;
    
    private Long orderNumber;
}
