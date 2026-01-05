package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 订单测试创建 dto
 */
@Data
@Schema(title="OrderCreateTestDto", description ="订单测试创建")
public class OrderCreateTestDto {
    
    
    @Schema(name ="programId", type ="Long", description ="节目表id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    
    @Schema(name ="userId", type ="Long", description ="用户id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long userId;
    
    @Schema(name ="haveException", type ="Integer", description ="是否有异常")
    private String haveException;
    
}
