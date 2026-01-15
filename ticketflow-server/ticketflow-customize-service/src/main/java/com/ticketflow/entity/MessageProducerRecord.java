package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 消息发送记录。可靠消息方案中的生产者消息记录，
 * 追踪消息的发送状态（未发送/发送成功/发送失败），
 * 支持异步确保和事务消息场景。
 * 数据表: d_message_producer_record
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("d_message_producer_record")
public class MessageProducerRecord extends BaseTableData {
    
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
