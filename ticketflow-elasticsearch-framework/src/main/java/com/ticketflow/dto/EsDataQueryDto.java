package com.ticketflow.dto;

import lombok.Data;

import java.util.Date;

/**
 * ES查询参数。封装Elasticsearch数据查询的条件参数。
 */
@Data
public class EsDataQueryDto {
    /**
     * 字段名
     * */
    private String paramName;
    /**
     * 字段值
     * */
    private Object paramValue;
    /**
     * 如果是时间类型，则不使用paramValue 使用startTime和endTime
     * */
    private Date startTime;
    /**
     * 如果是时间类型，则不使用paramValue 使用startTime和endTime
     * */
    private Date endTime;
    /**
     * 是否分词查询(默认不分词)
     * */
    private boolean analyse = false;
}
