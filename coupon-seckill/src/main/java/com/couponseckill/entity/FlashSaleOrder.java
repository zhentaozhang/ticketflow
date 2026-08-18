package com.couponseckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢购流水（逻辑表名 flash_sale_order，实际路由到 flash_sale_order_{0,1}）。
 */
@Data
@TableName("flash_sale_order")
public class FlashSaleOrder {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_ISSUED = 1;
    public static final int STATUS_ISSUE_FAILED = 2;
    public static final int STATUS_ROLLED_BACK = 3;

    @TableId(type = IdType.INPUT)
    private Long id;

    private String orderNo;

    private Long activityId;

    private Long userId;

    private String requestId;

    /** 发券成功后回填 user_coupon.id */
    private Long couponId;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
