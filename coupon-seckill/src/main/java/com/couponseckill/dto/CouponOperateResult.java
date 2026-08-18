package com.couponseckill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券锁定/核销后的返回（含面额快照，供订单金额计算）。
 */
@Data
public class CouponOperateResult {

    private String couponNo;

    private BigDecimal amount;

    private BigDecimal minAmount;

    private LocalDateTime validStart;

    private LocalDateTime validEnd;

    private String orderNo;

    public static CouponOperateResult of(com.couponseckill.entity.UserCoupon coupon) {
        CouponOperateResult r = new CouponOperateResult();
        r.couponNo = coupon.getCouponNo();
        r.amount = coupon.getAmount();
        r.minAmount = coupon.getMinAmount();
        r.validStart = coupon.getValidStart();
        r.validEnd = coupon.getValidEnd();
        r.orderNo = coupon.getOrderNo();
        return r;
    }
}
