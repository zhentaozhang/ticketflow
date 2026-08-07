package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
/**
 * 用户和购票人查询 dto
 */
@Data
@Schema(title="UserGetAndTicketUserListDto", description ="查询用户以及用户下购票人集合入参")
public class UserGetAndTicketUserListDto {
    
    @Schema(name ="userId", type ="Long", description ="用户id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long userId;
}
