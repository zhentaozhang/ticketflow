package com.ticketflow.vo;

import lombok.Data;

import java.util.Date;

/**
 * 消息发送记录 vo
 */
@Data
public class MessageProducerRecordVo {
    
    /**
     * 消息id
     */
    private Long id;
    
    /**
     * 消息类型，详见MessageType枚举
     */
    private Integer messageType;
    
    
    /**
     * 消息的链路id
     */
    private Long messageTraceId;
    
    /**
     * 消息业务id
     */
    private Long messageBusinessesId;
    
    /**
     * 消息id
     */
    private Long messageId;
    
    /**
     * 消息topic
     */
    private String messageTopic;
    
    /**
     * 消息内容
     */
    private String messageContent;
    
    /**
     * 消息发送失败的异常信息
     */
    private String messageSendException;
    
    /**
     * 消息发送状态 1:未发送 -1:发送失败 2:发送成功
     */
    private Integer messageSendStatus;
    
    /**
     * 消息对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕
     */
    private Integer reconciliationStatus;
    
    /**
     * 消息发送时间
     */
    private Date sendTime;
}
