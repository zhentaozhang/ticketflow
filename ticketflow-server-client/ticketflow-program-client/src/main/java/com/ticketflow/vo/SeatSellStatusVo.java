package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 座位售卖状态 vo（1未售卖 2锁定 3已售卖）。
 */
@Data
@Schema(title="SeatSellStatusVo", description ="座位售卖状态")
public class SeatSellStatusVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(name ="seatId", type ="Long", description ="座位id")
    private Long seatId;

    @Schema(name ="sellStatus", type ="Integer", description ="1未售卖 2锁定 3已售卖")
    private Integer sellStatus;
}
