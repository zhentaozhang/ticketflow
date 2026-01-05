package com.ticketflow.vo;

import lombok.Data;

import java.util.Date;

/**
 * 消息消费记录VO。消费者对消息的处理结果和状态。
 */
@Data
public class MessageConsumerRecordVo {


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
     * 消息消费失败的异常信息
     */
    private String messageConsumerException;

    /**
     * 消息消费状态 1:未消费 -1:消费失败 2:消费成功
     */
    private Integer messageConsumerStatus;
    
    /**
     * 消息的消费次数
     */
    private Integer messageConsumerCount;
    
    /**
     * 消息对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕
     */
    private Integer reconciliationStatus;
    
    /**
     * 消息发送时间
     */
    private Date consumerTime;
}
