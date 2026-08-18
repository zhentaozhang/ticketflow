package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 锁券返回（面额快照，供订单金额计算）。
 */
@Data
@Schema(description = "券面额快照")
public class CouponOperateVo {

    private String couponNo;

    private BigDecimal amount;

    private BigDecimal minAmount;

    private String orderNo;
}
