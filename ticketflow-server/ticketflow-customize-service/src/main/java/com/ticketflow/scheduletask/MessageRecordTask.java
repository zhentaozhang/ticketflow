package com.ticketflow.scheduletask;

import com.ticketflow.BusinessThreadPool;
import com.ticketflow.service.MessageRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息记录对账定时任务。定时扫描消息记录，确保生产者和消费者数据一致。
 */
@Slf4j
@Component
public class MessageRecordTask {
    
    @Autowired
    private MessageRecordService messageRecordService;

    @Scheduled(cron = "0 0/1 * * * ? ")
    public void reconciliationTask(){
        BusinessThreadPool.execute( () -> {
            messageRecordService.executeReconciliationTask();
        });
    }
}
