package com.ticketflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对账结果(记录类型维度)。按记录类型的Redis与DB对账差异结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationRecordTypeResult {

    /**
     * 记录类型编码 -1:扣减余票 0:改变状态 1:增加余票
     */
    private Integer recordTypeCode;

    /**
     * 记录类型值 -1:扣减余票(reduce) 0:改变状态(changeStatus) 1:增加余票(increase)
     * */
    private String recordTypeValue;

    /**
     * 座位的对账结果
     * */
    private ExaminationSeatResult examinationSeatResult;
}
