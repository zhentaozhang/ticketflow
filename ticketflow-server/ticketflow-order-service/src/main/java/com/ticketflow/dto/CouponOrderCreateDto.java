package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 券订单创建请求（M5 集成验证：模拟购票金额 + 用券抵扣）。
 */
@Data
@Schema(description = "券订单创建")
public class CouponOrderCreateDto {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "券号不能为空")
    private String couponNo;

    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于0")
    private BigDecimal amount;
}
