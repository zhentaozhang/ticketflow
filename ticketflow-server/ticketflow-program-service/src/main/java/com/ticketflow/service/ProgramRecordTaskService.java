package com.ticketflow.service;


import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.dto.ProgramRecordTaskAddDto;
import com.ticketflow.dto.ProgramRecordTaskListDto;
import com.ticketflow.dto.ProgramRecordTaskUpdateDto;
import com.ticketflow.entity.ProgramRecordTask;
import com.ticketflow.mapper.ProgramRecordTaskMapper;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramRecordTaskVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 节目对账记录任务服务。
 * order-service 在每次订单变更时创建记录，
 * 本服务接收查询/更新/添加请求，与 ReconciliationTask 配合完成数据对账
 */
@Service
public class ProgramRecordTaskService extends ServiceImpl<ProgramRecordTaskMapper, ProgramRecordTask> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private ProgramRecordTaskMapper programRecordTaskMapper;
    
    
    /**
     * 按处理状态与创建时间查询对账记录列表。
     *
     * @param programRecordTaskListDto 对账记录查询参数
     * @return 对账记录 Vo 列表
     */
    public List<ProgramRecordTaskVo> select(ProgramRecordTaskListDto programRecordTaskListDto){
        List<ProgramRecordTask> programRecordTaskList = 
                programRecordTaskMapper.selectList(Wrappers.lambdaQuery(ProgramRecordTask.class)
                        .eq(ProgramRecordTask::getHandleStatus, programRecordTaskListDto.getHandleStatus())
                        .le(ProgramRecordTask::getCreateTime, programRecordTaskListDto.getCreateTime()));
        return programRecordTaskList.stream().map(programRecordTask -> {
            ProgramRecordTaskVo programRecordTaskVo = new ProgramRecordTaskVo();
            BeanUtils.copyProperties(programRecordTask, programRecordTaskVo);
            return programRecordTaskVo;
        }).toList();
    }
    
    /**
     * 按创建时间批量更新处理状态。
     * 用于对账任务处理完成后标记已处理。
     *
     * @param programRecordTaskUpdateDto 对账记录更新参数
     * @return 受影响行数
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer updateByCreateTime(ProgramRecordTaskUpdateDto programRecordTaskUpdateDto){
        ProgramRecordTask updateProgramRecordTask = new ProgramRecordTask();
        updateProgramRecordTask.setHandleStatus(programRecordTaskUpdateDto.getAfterHandleStatus());
        return programRecordTaskMapper.update(updateProgramRecordTask,Wrappers.lambdaUpdate(ProgramRecordTask.class)
                        .eq(ProgramRecordTask::getHandleStatus, programRecordTaskUpdateDto.getBeforeHandleStatus())
                        .in(ProgramRecordTask::getCreateTime, programRecordTaskUpdateDto.getCreateTimeSet()));
        
    }
    
    /**
     * 新增对账记录。
     *
     * @param orderTicketUserRecordAddDto 对账记录新增参数
     * @return 插入行数
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer add(ProgramRecordTaskAddDto orderTicketUserRecordAddDto){
        ProgramRecordTask programRecordTask = new ProgramRecordTask();
        programRecordTask.setId(uidGenerator.getUid());
        programRecordTask.setProgramId(orderTicketUserRecordAddDto.getProgramId());
        programRecordTask.setCreateTime(DateUtils.now());
        programRecordTask.setEditTime(DateUtils.now());
        return programRecordTaskMapper.insert(programRecordTask);
    }
}
