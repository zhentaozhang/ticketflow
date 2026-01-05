package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 延迟订单取消 dto
 */
@Data
@Schema(title="DelayOrderCancelDto", description ="延迟订单取消")
public class DelayOrderCancelDto {
    
    @Schema(name ="programId", type ="Long", description ="节目id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    @Schema(name ="orderNumber", type ="Long", description ="订单编号",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long orderNumber;
}
