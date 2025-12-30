package com.ticketflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对账结果(精简汇总)。简化后的Redis与DB对账差异汇总结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationSimpleResult {

    /**
     * 节目id
     * */
    private Long programId;

    /**
     * 对比结果
     * */
    private List<ExaminationIdentifierResult> examinationIdentifierResultList;

    
}
