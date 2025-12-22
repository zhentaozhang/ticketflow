package com.ticketflow.dto;

import lombok.Data;

import java.util.Date;

/**
 * API调用记录接收参数。网关层接收的请求日志数据，用于异步记录API调用明细。
 */
@Data
public class ApiDataDto {

    private Long id;

    private String headVersion;

    private String apiAddress;

    private String apiMethod;

    private String apiBody;

    private String apiParams;

    private String apiUrl;

    private Date createTime;

    private Integer status;

    private String callDayTime;

    private String callHourTime;

    private String callMinuteTime;

    private String callSecondTime;

    private Integer type;

}
