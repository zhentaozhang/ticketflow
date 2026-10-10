package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 订单列表查询 dto
 */
@Data
@Schema(title="OrderListDto", description ="订单列表查询")
public class OrderListDto {
    
    @Schema(name ="userId", type ="Long", description ="用户id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long userId;

    @Schema(name ="page", type ="Integer", description ="页码，从 1 开始，默认 1")
    private Integer page;

    @Schema(name ="size", type ="Integer", description ="每页条数，默认 20，最大 50")
    private Integer size;

}
