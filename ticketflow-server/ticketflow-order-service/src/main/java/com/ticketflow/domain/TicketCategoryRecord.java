package com.ticketflow.domain;

import lombok.Data;

import java.util.List;

/**
 * Redis票档操作记录。记录对Redis中票档维度数据的变更操作。
 */
@Data
public class TicketCategoryRecord {
    
    private Long ticketCategoryId;
    private Long beforeAmount;
    private Long afterAmount;
    private Long changeAmount;
    private List<SeatRecord> seatRecordList;
}
