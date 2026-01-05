package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Job任务查询 dto
 */
@Data
@Schema(title="JobInfoPageDto", description ="job任务查询")
public class JobInfoPageDto {
    
    @Schema(name ="pageSize", type ="Integer", description ="页码", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageSize;
    
    @Schema(name ="pageNo", type ="Integer", description ="页容量", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageNo;
}
