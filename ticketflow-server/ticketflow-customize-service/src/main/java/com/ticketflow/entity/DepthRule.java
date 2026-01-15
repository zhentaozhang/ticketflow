package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serializable;

/**
 * 深度限流规则。在普通限流规则基础上增加时间窗口限定（起始~结束），
 * 支持按时间段动态调整限流策略，如高峰期放宽或收紧阈值。
 * 数据表: d_depth_rule
 */
@Data
@TableName("d_depth_rule")
public class DepthRule extends BaseTableData implements Serializable {
    
    private Long id;
    
    private String startTimeWindow;
    
    private String endTimeWindow;

    private Integer statTime;
    
    private Integer statTimeType;
    
    private Integer threshold;
    
    private Integer effectiveTime;
    
    private Integer effectiveTimeType;
    
    private String limitApi;
    
    private String message;
}
