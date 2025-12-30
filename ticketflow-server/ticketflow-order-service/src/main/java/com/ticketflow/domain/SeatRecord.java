package com.ticketflow.domain;

import lombok.Data;

/**
 * Redis座位操作记录。记录对Redis中座位数据的变更，包括票档、座位和购票人信息。
 */
@Data
public class SeatRecord {
    
    private Long ticketCategoryId;
    private Long seatId;
    private Long ticketUserId;
    private Integer beforeStatus;
    private Integer afterStatus;
}
