package com.couponseckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板（对应表 coupon_template）。
 */
@Data
@TableName("coupon_template")
public class CouponTemplate {

    public static final int TYPE_FULL_REDUCTION = 1;
    public static final int TYPE_DISCOUNT = 2;

    public static final int VALID_TYPE_FIXED = 1;
    public static final int VALID_TYPE_DAYS = 2;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.INPUT)
    private Long id;

    private String templateNo;

    private String name;

    private Integer type;

    private BigDecimal amount;

    private BigDecimal minAmount;

    private Integer validType;

    private LocalDateTime validStart;

    private LocalDateTime validEnd;

    private Integer validDays;

    private Integer scope;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
