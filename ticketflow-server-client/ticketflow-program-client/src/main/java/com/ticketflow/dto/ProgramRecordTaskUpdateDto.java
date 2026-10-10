package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * 节目对账记录任务 dto
 */
@Data
@Schema(title="ProgramRecordTaskUpdateDto", description ="节目对账记录修改任务")
public class ProgramRecordTaskUpdateDto {
    
    @Schema(name ="idSet", type ="Set<Long>", description ="对账记录主键id集合",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Set<Long> idSet;
    
    @Schema(name ="beforeHandleStatus", type ="Integer", description ="修改之前的处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer beforeHandleStatus;
    
    @Schema(name ="afterHandleStatus", type ="Integer", description ="修改之后的处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer afterHandleStatus;
}
