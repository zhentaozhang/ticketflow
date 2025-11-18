package com.ticketflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * ES地理排序参数。封装基于地理位置的排序条件，包含坐标和排序方向。
 */
@Data
public class EsGeoPointSortDto {
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
