package com.ticketflow.service.delaysend;

import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.core.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TIME;
import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TIME_UNIT;
import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TOPIC;

/**
 * 延迟队列发送器：订单支付成功后触发节目数据更新。
 * 支付成功后不是立即更新座位/余票，而是向延迟队列推送一条消息，
 * 延迟时间后由 DelayOperateProgramDataConsumer 消费执行。
 *
 * 延迟目的：确保 DB 事务已提交 + Redis 主从同步完成后再操作缓存
 */
@Slf4j
@Component
public class DelayOperateProgramDataSend {
    
    @Autowired
    private DelayQueueContext delayQueueContext;
    
    public void sendMessage(String message){
        try {
            delayQueueContext.sendMessage(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_OPERATE_PROGRAM_DATA_TOPIC,
                    message, DELAY_OPERATE_PROGRAM_DATA_TIME, DELAY_OPERATE_PROGRAM_DATA_TIME_UNIT);
        }catch (Exception e) {
            log.error("send message error message : {}",message,e);
        }
        
    }
}
