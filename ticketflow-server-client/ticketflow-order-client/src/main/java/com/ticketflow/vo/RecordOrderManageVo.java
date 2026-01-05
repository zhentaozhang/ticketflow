package com.ticketflow.vo;

import lombok.Data;

import java.util.List;

/**
 * 对账订单管理VO。记录订单对账结果，包含购票人维度差异数据列表。
 */
@Data
public class RecordOrderManageVo {
    
    private Long programId;
    
    private Long orderNumber;
    
    private Long userId;
    
    private Integer reconciliationStatus;
    
    private String reconciliationStatusName;
    
    private List<RecordOrderTickerUserManageVo> recordOrderTickerUserManageVoList; 
}
