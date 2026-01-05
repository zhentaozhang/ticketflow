package com.ticketflow.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 对账购票人管理VO。记录订单对账中购票人维度的差异数据。
 */
@Data
public class RecordOrderTickerUserManageVo {
    
    private Long ticketUserOrderId;
    
    private Long ticketUserId;
    
    private Long seatId;
    
    private String seatInfo;
    
    private String redisBeforeSeatStatusName;
    
    private String redisAfterSeatStatusName;
    
    private Long ticketCategoryId;
    
    private String ticketCategoryName;
    
    private BigDecimal orderPrice;
    
    private Integer dbRecordTypeCode;
    
    private String dbRecordTypeValue;
    
    private String dbRecordTypeName;
    
    private String redisRecordTypeName;
    
    private Integer reconciliationStatus;
    
    private String reconciliationStatusName;
    
    
}
