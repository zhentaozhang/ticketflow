package com.ticketflow.vo;

import lombok.Data;

import java.util.Date;

/**
 * 消息记录视图返回对象
 */
@Data
public class MessageRecordVo {
    
    /**
     * 消息的发送记录id
     */
    private Long messageProducerRecordId;
    /**
     * 消息的消费记录id
     */
    private Long messageConsumerRecordId;
    /**
     * 消息的类型，详见MessageType枚举
     */
    private Integer messageType;
    /**
     * 消息的类型名字，详见MessageType枚举
     */
    private String messageTypeName;
    /**
     * 消息的链路id
     */
    private Long messageTraceId;
    /**
     * 消息的业务id
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
     * 消息消费失败的异常信息
     */
    private String messageConsumerException;

    /**
     * 消息发送状态 1:未发送 -1:发送失败 2:发送成功
     */
    private Integer messageSendStatus;
    
    
    private String messageSendStatusName;
    
    /**
     * 消息消费状态 1:未消费 -1:消费失败 2:消费成功
     */
    private Integer messageConsumerStatus;
    
    
    private String messageConsumerStatusName;
    
    /**
     * 消息的消费次数
     */
    private Integer messageConsumerCount;
    
    /**
     * 消息对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕
     */
    private Integer reconciliationStatus;
    
    private String reconciliationStatusName;
    
    /**
     * 消息发送时间
     */
    private Date sendTime;
    /**
     * 消息消费时间
     */
    private Date consumerTime;
}
