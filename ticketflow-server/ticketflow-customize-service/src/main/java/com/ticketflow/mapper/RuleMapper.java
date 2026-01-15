package com.ticketflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.Rule;

/**
 * 规则表 Mapper
 */
public interface RuleMapper extends BaseMapper<Rule> {
    
    /**
     * 删除所有规则
     * @return 结果
     * */
    int delAll();
}
