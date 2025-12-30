package com.ticketflow.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramRecordTaskListDto;
import com.ticketflow.dto.ProgramRecordTaskUpdateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.HandleStatus;
import com.ticketflow.service.OrderTaskService;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramRecordTaskVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Redis ↔ DB 数据对账定时任务（默认3分钟间隔，已注释）。
 * 扫描 ProgramRecordTask 中未处理的记录，
 * 检测 Redis 中 lock 超时的座位，清理并恢复余票，
 * 确保极端情况下座位数据最终一致
 */
@Slf4j
@Component
public class ReconciliationTask {

    @Autowired
    private OrderTaskService orderTaskService;
    
    @Autowired
    private ProgramClient programClient;

    //@Scheduled(cron = "0 0/3 * * * ? ")
    public void reconciliationTask(){
        BusinessThreadPool.execute( () -> {
            try {
                log.info("对账任务执行");
                ProgramRecordTaskListDto programRecordTaskListDto = new ProgramRecordTaskListDto();
                programRecordTaskListDto.setHandleStatus(HandleStatus.NO_HANDLE.getCode());
                //查询当前时间前3分钟的对账记录
                programRecordTaskListDto.setCreateTime(DateUtils.addMinute(DateUtils.now(),-3));
                ApiResponse<List<ProgramRecordTaskVo>> listApiResponse = programClient.select(programRecordTaskListDto);
                if (!Objects.equals(listApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                    log.error("获取节目对账记录任务集合失败 dto : {} message: {}", JSON.toJSONString(programRecordTaskListDto),listApiResponse.getMessage());
                    return;
                }
                List<ProgramRecordTaskVo> programRecordTaskVoList = listApiResponse.getData();
                if (CollectionUtil.isEmpty(programRecordTaskVoList)) {
                    log.warn("获取节目对账记录任务集合为空 dto : {}",JSON.toJSONString(programRecordTaskListDto));
                    return;
                }
                Set<Long> programIdSet = new HashSet<>();
                Set<Date> createTimeSet = new HashSet<>();
                for (ProgramRecordTaskVo programRecordTaskVo : programRecordTaskVoList) {
                    programIdSet.add(programRecordTaskVo.getProgramId());
                    createTimeSet.add(programRecordTaskVo.getCreateTime());
                }
                for (Long programId : programIdSet) {
                    orderTaskService.reconciliationTask(programId);
                }
                //修改对账记录任务集合为已处理
                ProgramRecordTaskUpdateDto programRecordTaskUpdateDto = new ProgramRecordTaskUpdateDto();
                programRecordTaskUpdateDto.setBeforeHandleStatus(HandleStatus.NO_HANDLE.getCode());
                programRecordTaskUpdateDto.setAfterHandleStatus(HandleStatus.YES_HANDLE.getCode());
                programRecordTaskUpdateDto.setCreateTimeSet(createTimeSet);
                ApiResponse<Integer> updateApiResponse = programClient.update(programRecordTaskUpdateDto);
                if (!Objects.equals(listApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                    log.error("更新节目对账记录任务失败 dto : {} message: {}", JSON.toJSONString(programRecordTaskUpdateDto),updateApiResponse.getMessage());
                }
            }catch (Exception e) {
                log.error("reconciliation task error",e);
            }
        });
    }
}
