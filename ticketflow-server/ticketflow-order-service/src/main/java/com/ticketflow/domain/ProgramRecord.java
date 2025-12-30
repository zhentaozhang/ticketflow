package com.ticketflow.domain;

import lombok.Data;

import java.util.List;

/**
 * Redis节目操作记录。记录对Redis中节目维度数据的变更操作。
 */
@Data
public class ProgramRecord {
    
    private Long timestamp;
    
    private String recordType;
    
    private List<TicketCategoryRecord> ticketCategoryRecordList;
}
