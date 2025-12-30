package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.OrderProgram;

/**
 * 订单节目关联表 Mapper
 */
public interface OrderProgramMapper extends BaseMapper<OrderProgram> {
    
    /**
     * 真实删除订单节目数据
     * @return 结果
     * */
    Integer relDelOrderProgram();
}
