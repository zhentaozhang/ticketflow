package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 节目票档详情 Vo
 */
@Data
@Schema(title="TicketCategoryDetailManageVo", description ="节目票档详情")
public class TicketCategoryDetailManageVo {

    @Schema(name ="id", type ="Long", description ="节目票档id",requiredMode= RequiredMode.REQUIRED)
    private Long id;
    
    @Schema(name ="programId", type ="Long", description ="节目表id",requiredMode= RequiredMode.REQUIRED)
    private Long programId;
    
    @Schema(name ="introduce", type ="String", description ="介绍",requiredMode= RequiredMode.REQUIRED)
    private String introduce;
    
    @Schema(name ="price", type ="BigDecimal", description ="价格",requiredMode= RequiredMode.REQUIRED)
    private BigDecimal price;
    
    @Schema(name ="totalNumber", type ="Long", description ="总数量",requiredMode= RequiredMode.REQUIRED)
    private Long totalNumber;
    
    @Schema(name ="dbRemainNumber", type ="Long", description ="数据库中剩余数量",requiredMode= RequiredMode.REQUIRED)
    private Long dbRemainNumber;
    
    @Schema(name ="redisRemainNumber", type ="Long", description ="Redis中剩余数量",requiredMode= RequiredMode.REQUIRED)
    private Long redisRemainNumber;
}
