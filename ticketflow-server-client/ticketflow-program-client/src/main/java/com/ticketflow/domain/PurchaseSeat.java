package com.ticketflow.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购票座位。记录用户下单时选择的座位信息。
 */
@Data
@Schema(title = "PurchaseSeat", description = "座位")
public class PurchaseSeat implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;


    @Schema(name = "id", type = "Long", description = "座位id")
    private Long id;

    @Schema(name = "programId", type = "Long", description = "节目表id")
    private Long programId;

    @Schema(name = "ticketCategoryId", type = "Long", description = "节目票档id")
    private Long ticketCategoryId;

    @Schema(name = "ticketUserId", type = "Long", description = "购票人id")
    private Long ticketUserId;

    @Schema(name = "rowCode", type = "Integer", description = "排号")
    private Integer rowCode;

    @Schema(name = "colCode", type = "Integer", description = "列号")
    private Integer colCode;

    @Schema(name = "seatType", type = "Integer", description = "座位类型")
    private Integer seatType;

    @Schema(name = "seatTypeName", type = "String", description = "座位类型名")
    private String seatTypeName;

    @Schema(name = "price", type = "BigDecimal", description = "座位价格")
    private BigDecimal price;

    @Schema(name = "sellStatus", type = "Integer", description = "1未售卖 2锁定 3已售卖")
    private Integer sellStatus;
}
