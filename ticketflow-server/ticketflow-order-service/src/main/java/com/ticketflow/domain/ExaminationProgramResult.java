package com.ticketflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对账结果(节目维度)。按节目维度的Redis与DB对账差异结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationProgramResult {
    
    /**
     * 记录标识的集合
     * */
    private List<ExaminationIdentifierResult> examinationIdentifierResultList;
}
