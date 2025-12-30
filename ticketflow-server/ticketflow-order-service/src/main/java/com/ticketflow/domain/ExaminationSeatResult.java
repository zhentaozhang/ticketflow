package com.ticketflow.domain;

import com.ticketflow.entity.OrderTicketUserRecord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对账结果(座位维度)。按座位维度的Redis与DB对账差异结果，以数据库为准。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationSeatResult {
    
    /**
     * Redis和数据库匹配的座位数量
     * */
    private int matchCount;

    /**
     * 需要向redis中补充的座位（数据库有但Redis没有）
     * */
    private List<OrderTicketUserRecord> needToRedisSeatRecordList;
}
