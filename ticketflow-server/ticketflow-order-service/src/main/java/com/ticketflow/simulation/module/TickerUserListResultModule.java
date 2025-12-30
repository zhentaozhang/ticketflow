package com.ticketflow.simulation.module;

import com.ticketflow.vo.TicketUserVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 压测购票人列表结果模块。模拟购票人查询接口返回结果的数据结构。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TickerUserListResultModule extends ApiResponseModule{

    private List<TicketUserVo> data;
}
