package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用券契约 DTO（对齐 coupon 服务 /coupon/lock|use|return 请求体字段）。
 */
@Data
@Schema(description = "用券操作")
public class CouponOperateDto {

    @NotBlank(message = "券号不能为空")
    private String couponNo;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
