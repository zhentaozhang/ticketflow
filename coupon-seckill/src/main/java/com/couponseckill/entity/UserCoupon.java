package com.couponseckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券（逻辑表名 user_coupon，实际路由到 user_coupon_{0,1}）。
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    public static final int STATUS_UNUSED = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_USED = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_INVALIDATED = 4;

    @TableId(type = IdType.INPUT)
    private Long id;

    private String couponNo;

    private Long userId;

    private Long activityId;

    private Long templateId;

    private BigDecimal amount;

    private BigDecimal minAmount;

    private LocalDateTime validStart;

    private LocalDateTime validEnd;

    private Integer status;

    private String orderNo;

    private LocalDateTime lockTime;

    private LocalDateTime useTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
