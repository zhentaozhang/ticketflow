package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.ProgramRecordTask;

/**
 * 节目对账记录任务表 Mapper
 */
public interface ProgramRecordTaskMapper extends BaseMapper<ProgramRecordTask> {
    /**
     * 真实删除节目对账记录任务数据
     * @return 结果
     * */
    Integer relDelProgramRecordTask();
}
