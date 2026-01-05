package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 添加消息消费记录 dto
 */
@Data
@Schema(title="InsertMessageConsumerRecordDto", description ="添加消息消费记录")
public class InsertMessageConsumerRecordDto {
    
    @Schema(name ="messageType", type ="Integer", description ="消息类型", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer messageType;
    
    /**
     * 消息的链路id
     */
    @Schema(name ="messageTraceId", type ="Long", description ="消息的链路id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long messageTraceId;
    
    /**
     * 消息业务id
     */
    @Schema(name ="messageBusinessesId", type ="Long", description ="消息业务id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long messageBusinessesId;
    
    /**
     * 消息id
     */
    @Schema(name ="messageId", type ="Long", description ="消息id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long messageId;
    
    /**
     * 消息topic
     */
    @Schema(name ="messageTopic", type ="String", description ="消息topic", requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String messageTopic;
    
    /**
     * 消息内容
     */
    @Schema(name ="messageContent", type ="String", description ="消息内容", requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String messageContent;
}
