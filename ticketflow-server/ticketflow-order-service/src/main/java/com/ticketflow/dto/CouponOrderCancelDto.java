package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 券订单取消请求（模拟支付失败/订单取消 → 退券）。
 */
@Data
@Schema(description = "券订单取消")
public class CouponOrderCancelDto {

    @NotBlank(message = "券号不能为空")
    private String couponNo;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
