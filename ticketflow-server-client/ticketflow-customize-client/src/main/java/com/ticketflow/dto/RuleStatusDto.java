package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 普通规则状态修改 dto
 */
@Data
@Schema(title="RuleStatusDto", description ="普通规则状态修改")
public class RuleStatusDto {
    
    @Schema(name ="id", type ="String", description ="普通规则id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long id;
    
    @Schema(name ="status", type ="Integer", description ="状态 1生效 0禁用", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer status;
}
