package com.ticketflow.handler;

import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageType;

import java.util.List;

/**
 * 异常消息处理器接口——策略模式。
 *
 * 不同类型的异常消息由不同的实现类处理（如延迟订单取消、退款等），
 * 由 ExceptionMessageHandlerContext 按消息类型分发
 */
public interface ExceptionMessageHandler {
    
    /**
     * 获取没有对账的消息发送记录集合
     * @return 结果
     * */
    List<MessageProducerRecord> noReconciliationMessageProducerRecordList();
    
    /**
     * 处理消息
     * @param messageProducerRecord 消息记录
     * @return 结果
     * */
    Boolean handle(MessageProducerRecord messageProducerRecord);
    
    /**
     * 获取消息类型
     * @return 结果
     * */
    MessageType getMessageType();
}
