package com.ticketflow.service;

import lombok.Data;

/**
 * API限流统计数据。记录单个API的触发结果、统计调用次数和阈值信息。
 */
@Data
public class ApiRestrictData {

    // 限流规则触发结果
    // 例如：是否触发限流
    private Long triggerResult;

    // 当前 API 调用统计值
    // 用于记录当前时间窗口内 API 调用次数
    private Long triggerCallStat;

    // API 当前累计调用次数
    private Long apiCount;

    // API 设置的限流阈值
    // 当调用次数超过该值时触发限流
    private Long threshold;

    // 提示信息索引
    // 用于获取对应的限流提示信息
    private Long messageIndex;
}
