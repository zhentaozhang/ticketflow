package com.ticketflow.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 节目+演出时间联合视图。继承 Program，连表查询时携带演出时间字段，
 * 用于列表展示等需要同时展示节目信息和最近场次时间的场景。
 */
@Data
public class ProgramJoinShowTime extends Program implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * 演出时间
     */
    private Date showTime;
    
    /**
     * 演出时间(精确到天)
     */
    private Date showDayTime;
    
    /**
     * 演出时间所在的星期
     */
    private String showWeekTime;
}
