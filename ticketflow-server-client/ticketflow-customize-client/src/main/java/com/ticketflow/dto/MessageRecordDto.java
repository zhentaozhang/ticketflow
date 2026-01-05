package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消息记录 dto
 */
@Data
@Schema(title="MessageRecordDto", description ="消息记录")
public class MessageRecordDto extends BasePageDto {
    
    /**
     * 消息业务id
     */
    @Schema(name ="messageBusinessesId", type ="Long", description ="消息业务id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long messageBusinessesId;
}
