package com.ticketflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对账结果(记录标识维度)。按记录ID维度的Redis与DB对账差异结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationIdentifierResult {

    /**
     * 记录标识
     * */
    private String identifierId;

    /**
     * 用户id
     * */
    private String userId;
    
    /**
     * 记录类型的集合
     * */
    List<ExaminationRecordTypeResult> examinationRecordTypeResultList;
}
