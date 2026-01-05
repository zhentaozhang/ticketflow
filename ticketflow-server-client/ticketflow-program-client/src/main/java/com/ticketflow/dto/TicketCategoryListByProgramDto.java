package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Collection;

/**
 * 节目票档集合 dto
 */
@Data
@Schema(title="TicketCategoryListByProgramDto", description ="节目票档集合")
public class TicketCategoryListByProgramDto {
    
    @Schema(name ="programId", type ="Long", description ="节目id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
}
