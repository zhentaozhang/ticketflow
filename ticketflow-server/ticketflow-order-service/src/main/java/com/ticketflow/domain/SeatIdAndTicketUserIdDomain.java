package com.ticketflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 座位购票人关联。存储座位ID与购票人ID的配对关系。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatIdAndTicketUserIdDomain {

    private Long seatId;
    
    private Long ticketUserId;
}
