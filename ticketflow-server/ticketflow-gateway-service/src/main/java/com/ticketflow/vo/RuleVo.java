package com.ticketflow.vo;

import lombok.Data;

/**
 * 限流规则视图对象。网关层下发给客户端的普通限流规则数据。
 */
@Data
public class RuleVo {
    
    private String id;
    
    private Integer statTime;
    
    private Integer statTimeType;
    
    private Integer threshold;
    
    private Integer effectiveTime;
    
    private Integer effectiveTimeType;
    
    private String limitApi;
    
    private String message;
    
    private Integer status;
}
