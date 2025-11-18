package com.ticketflow.dto;

import lombok.Data;

/**
 * ES数据创建参数。封装Elasticsearch索引文档的数据字段配置。
 */
@Data
public class EsDataCreateDto {
    
    /**
     * 字段名
     * */
    private String paramName;
    /**
     * 字段值
     * */
    private Object paramValue;
}
