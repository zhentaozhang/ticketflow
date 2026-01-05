package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单查询 dto
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(title="OrderPageManageDto", description ="订单")
public class OrderPageManageDto extends BasePageDto{
    
    @Schema(name ="programId", type ="Long", description ="id",requiredMode= RequiredMode.NOT_REQUIRED)
    private Long programId;
}
