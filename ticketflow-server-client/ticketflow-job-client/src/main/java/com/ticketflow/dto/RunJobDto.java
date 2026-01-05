package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Job任务执行 dto
 */
@Data
@Schema(title = "RunJobDto", description = "RunJobDto")
public class RunJobDto {

    @Schema(name = "id", type = "Long", description = "任务id", requiredMode = RequiredMode.REQUIRED)
    @NotNull
    private Long id;
}
