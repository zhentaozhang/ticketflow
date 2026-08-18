package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 券订单结果。
 */
@Data
@Schema(description = "券订单结果")
public class CouponOrderVo {

    /** 订单号 */
    private String orderNo;

    private String couponNo;

    /** 订单原价 */
    private BigDecimal originalAmount;

    /** 券抵扣金额 */
    private BigDecimal discountAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** PAID=已支付 CANCELLED=已取消 */
    private String status;
}
