package com.ticketflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ES文档映射。定义Elasticsearch索引中文档字段的映射关系。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EsDocumentMappingDto {
    
    /**
     * 字段名
     * */
    private String paramName;
    
    /**
     * 字段类型
     * */
    private String paramType;
}
