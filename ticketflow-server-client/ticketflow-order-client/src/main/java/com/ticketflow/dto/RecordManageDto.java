package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 节目记录查询 dto
 */
@Data
public class RecordManageDto {
    
    @Schema(name ="programId", type ="Long", description ="id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    
    @Schema(name ="pageNumber", type ="Long", description ="页码",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageNumber;
    
    @Schema(name ="pageSize", type ="Long", description ="页大小",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageSize;
    
    
}
