package com.ticketflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * ES地理坐标点。封装经纬度，用于Elasticsearch的地理位置查询和排序。
 */
@Data
public class EsGeoPointDto {
    /**
     * 字段名
     * */
    private String paramName;
    /**
     * 纬度值
     * */
    private BigDecimal latitude;
    /**
     * 经度值
     * */
    private BigDecimal longitude;
    
    
}
