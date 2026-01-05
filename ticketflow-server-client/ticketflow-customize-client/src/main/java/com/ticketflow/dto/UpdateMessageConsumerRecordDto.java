package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 更新消息消费记录 实体
 */
@Data
public class UpdateMessageConsumerRecordDto implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="id", type ="Long", description ="消息消费记录id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
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
