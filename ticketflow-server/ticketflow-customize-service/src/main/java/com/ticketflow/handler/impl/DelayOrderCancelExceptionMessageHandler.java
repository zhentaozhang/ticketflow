package com.ticketflow.handler.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.handler.ExceptionMessageHandler;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME;
import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT;

/**
 * 延迟订单取消异常处理器——处理订单取消失败的补偿逻辑。
 *
 * 调用 order-service 的延迟取消接口，
 * 在首次取消失败时记录异常信息供后续重试
 */
@Slf4j
@Component
public class DelayOrderCancelExceptionMessageHandler implements ExceptionMessageHandler {
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private DelayQueueContext delayQueueContext;
    
    @Override
    public List<MessageProducerRecord> noReconciliationMessageProducerRecordList() {
        // 计算"合理的发送时间边界"：当前时间 - 延迟时间（即该消息理论上应被消费的时间点）
        Date date;
        switch (DELAY_ORDER_CANCEL_TIME_UNIT) {
            case MINUTES -> date = DateUtils.addMinute(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case HOURS -> date = DateUtils.addHour(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case DAYS -> date = DateUtils.addDay(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            default -> date = DateUtils.addSecond(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
        }
        // 查询未对账且发送时间 < 理论消费时间 - 10s 的消息（超过等待期仍未对账的异常消息）
        Wrapper<MessageProducerRecord> messageRecordWrapper = Wrappers.lambdaQuery(MessageProducerRecord.class)
                .eq(MessageProducerRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .lt(MessageProducerRecord::getSendTime, DateUtils.addSecond(date, -10));
        return messageProducerRecordMapper.selectList(messageRecordWrapper);
    }
    
    @Override
    public Boolean handle(MessageProducerRecord messageProducerRecord) {
        String messageContent = messageProducerRecord.getMessageContent();
        MessageProducerRecord udpateMessageProducerRecord = new MessageProducerRecord();
        udpateMessageProducerRecord.setId(messageProducerRecord.getId());
        udpateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        try {
            log.info("延迟订单取消消息进行发送 消息体 : {}",messageContent);
            // 异常补偿：延迟改为 1 秒（立即消费），而非原始延迟时间
            delayQueueContext.sendMessage(messageProducerRecord.getMessageTopic(), messageContent, 1, TimeUnit.SECONDS);
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_SUCCESS.getCode());
        }catch (Exception e) {
            log.error("send message error message : {}",messageContent,e);
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
            udpateMessageProducerRecord.setMessageSendException(e.getMessage());
        }
        messageProducerRecordMapper.updateById(udpateMessageProducerRecord);
        return true;
    }
    
    @Override
    public MessageType getMessageType() {
        return MessageType.DELAY_ORDER_CANCEL;
    }
}
