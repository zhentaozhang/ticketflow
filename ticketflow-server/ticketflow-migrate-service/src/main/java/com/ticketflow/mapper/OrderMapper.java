package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.Order;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单表 Mapper（迁移服务使用）
 */
public interface OrderMapper extends BaseMapper<Order> {
    
    /**
     * 物理删除订单 
     * @param ids 订单 id 列表
     * @return Integer 结果
     * */
    Integer physicalDeleteByIds(@Param("ids") List<Long> ids);
}
