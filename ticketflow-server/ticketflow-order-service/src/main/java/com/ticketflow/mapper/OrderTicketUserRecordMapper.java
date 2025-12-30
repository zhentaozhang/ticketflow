package com.ticketflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.OrderTicketUserRecord;

/**
 * 订单-购票人执行记录表 Mapper
 */
public interface OrderTicketUserRecordMapper extends BaseMapper<OrderTicketUserRecord> {
    
    /**
     * 真实删除购票人订单记录数据
     * @return 结果
     * */
    Integer relDelOrderTicketUserRecord();

}
