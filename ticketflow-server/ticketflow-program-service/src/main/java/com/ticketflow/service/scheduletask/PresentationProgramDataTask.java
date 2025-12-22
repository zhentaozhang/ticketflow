package com.ticketflow.service.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.dto.ProgramResetExecuteDto;
import com.ticketflow.mapper.ProgramRecordTaskMapper;
import com.ticketflow.service.ProgramService;
import com.ticketflow.service.init.ProgramElasticsearchInitData;
import com.ticketflow.service.init.ProgramShowTimeRenewal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 节目服务每日定时任务（cron = "0 0 23 * * ?" 每晚 23 点）。
 * 功能：
 *   1. 全量节目 ES 数据重建（删除旧索引 → 写入最新数据）
 *   2. 演出时间自动过期处理（showTime < now → 标记已结束）
 *   3. 未完成的对账记录任务自动触发重试
 *
 * 通过 BusinessThreadPool 异步执行防阻塞
 */
@Slf4j
@Component
public class PresentationProgramDataTask {
    
    @Autowired
    private ConfigurableApplicationContext applicationContext;
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private ProgramShowTimeRenewal programShowTimeRenewal;
    
    @Autowired
    private ProgramElasticsearchInitData programElasticsearchInitData;
    
    @Autowired
    private ProgramRecordTaskMapper programRecordTaskMapper;
    
    
    /**
     * 每日定时任务入口（每晚 23:00 执行）。
     * <p>
     * 执行顺序：
     * 1. DB 座位/票档数据重置 + Redis/本地缓存全量清理（为次日售票做准备）
     * 2. 清理历史对账记录
     * 3. 已过期场次自动向前滚动并清除关联缓存
     * 4. 全量重建 ES 索引，保证次日搜索数据最新
     * <p>
     * 全部在 BusinessThreadPool 异步线程中执行，避免阻塞 Spring 调度线程。
     */
    @Scheduled(cron = "0 0 23 * * ?")
    public void executeTask(){
        BusinessThreadPool.execute( () -> {
            try {
                log.info("节目服务定时任务重置执行");
                List<Long> allProgramIdList = programService.getAllProgramIdList();
                if (CollectionUtil.isNotEmpty(allProgramIdList)) {
                    for (Long programId : allProgramIdList) {
                        ProgramResetExecuteDto programResetExecuteDto = new ProgramResetExecuteDto();
                        programResetExecuteDto.setProgramId(programId);
                        // 第一步：DB 座位/票档数量重置 + Redis/本地缓存全量删除（全量刷新，为次日售票准备）
                        programService.resetExecute(programResetExecuteDto);
                    }
                }
                // 第二步：清理对账记录（生产环境仅专版执行，普通版本该 Mapper 为 no-op）
                programRecordTaskMapper.relDelProgramRecordTask();
                // 第三步：已过期场次日期自动向前滚动（按月步长），同时清除关联的 ES 索引和 Redis 缓存
                programShowTimeRenewal.executeInit(applicationContext);
                // 第四步：全量重建 ES 索引（覆盖价格、场次、座位等信息，保证次日搜索数据最新）
                programElasticsearchInitData.executeInit(applicationContext);
            }catch (Exception e) {
                log.error("executeTask error",e);
            }
        });
    }
}
