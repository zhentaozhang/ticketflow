package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * 节目对账记录任务 dto
 */
@Data
@Schema(title="ProgramRecordTaskListDto", description ="节目对账记录查询任务")
public class ProgramRecordTaskListDto {
    
    @Schema(name ="createTime", type ="Date", description ="创建时间",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Date createTime;
    
    @Schema(name ="handleStatus", type ="Integer", description ="处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer handleStatus;
}
