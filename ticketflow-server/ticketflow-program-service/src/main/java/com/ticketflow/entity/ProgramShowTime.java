package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 节目演出时间实体 —— 对应数据库表 d_program_show_time。
 *
 * 一场演出（Program）可能有多个场次。
 * 比如"周杰伦2024巡回演唱会"这个 Program，
 *   场次 1：2024-01-05 19:30（北京）
 *   场次 2：2024-01-06 19:30（北京）
 * ...
 * 每个场次是一条 ProgramShowTime 记录。
 *
 * showTime = 精确到秒的演出时间
 * showDayTime = 精确到天的日期（用于按日期范围搜索）
 * showWeekTime = "周五"这种文本（前端直接展示）
 */
@Data
@TableName("d_program_show_time")
public class ProgramShowTime extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 关联节目表 d_program 的 id。
     * 一个 Program 对应多条 ProgramShowTime。
     */
    private Long programId;

    /**
     * 演出具体时间（精确到秒）。
     * 比如 2024-01-05 19:30:00。
     * 用于在详情页展示"2024-01-05 19:30 周五"。
     */
    private Date showTime;

    /**
     * 演出日期（精确到天）。
     * 比如 2024-01-05。
     * 用于按日期范围搜索（"只看本周的演出"）。
     */
    private Date showDayTime;

    /**
     * 演出星期文本。
     * 比如"周五"。
     * 前端直接拿来展示，不用再算星期几。
     */
    private String showWeekTime;
}
