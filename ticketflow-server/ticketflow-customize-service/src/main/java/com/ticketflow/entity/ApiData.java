package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serializable;

/**
 * API调用记录。记录所有经过网关的API请求明细，
 * 包含请求地址、方法、参数、Body及时间维度统计。
 * 数据表: d_api_data
 */
@Data
@TableName("d_api_data")
public class ApiData extends BaseTableData implements Serializable {
    
    private Long id;
    
    private String headVersion;
    
    private String apiAddress;
    
    private String apiMethod;
    
    private String apiBody;
    
    private String apiParams;
    
    private String apiUrl;
    
    private String callDayTime;
    
    private String callHourTime;
    
    private String callMinuteTime;
    
    private String callSecondTime;
    
    private Integer type;
}
