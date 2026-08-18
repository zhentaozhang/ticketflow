package com.couponseckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 锁券/核销/退回请求（与 order-service 的集成契约，docs/01-技术设计.md §12.3）。
 */
@Data
public class CouponOperateRequest {

    @NotBlank(message = "券号不能为空")
    private String couponNo;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
