package com.ticketflow.scheduletask;

import com.ticketflow.BusinessThreadPool;
import com.ticketflow.service.MessageRecordService;
import com.ticketflow.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息记录清理定时任务。定时删除过期消息记录，避免数据堆积。
 * 保护机制：生产环境默认关闭，仅在显式配置开启时才执行物理清空，避免清空可靠消息审计记录。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketflow.task.presentation.message-clean.enabled", havingValue = "true", matchIfMissing = false)
public class PresentationMessageRecordTask {
    
    @Autowired
    private MessageRecordService messageRecordService;
    
    @Scheduled(cron = "0 0 23 * * ?")
    public void executeTask(){
        BusinessThreadPool.execute( () -> {
            //删除所有的消息记录数据
            log.info("开始删除所有消息记录数据");
            messageRecordService.deleteMessageRecord(DateUtils.now());
        });
    }
}
