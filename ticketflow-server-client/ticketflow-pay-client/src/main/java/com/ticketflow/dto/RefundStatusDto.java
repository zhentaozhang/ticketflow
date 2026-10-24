package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 退款单状态查询 dto。
 *
 * <p>同时带 outOrderNo 与 outRefundNo：d_refund_bill 按 out_order_no 分片，
 * 只给退款单号会让分片路由退化成全库广播。
 */
@Data
@Schema(title = "RefundStatusDto", description = "退款单状态查询")
public class RefundStatusDto {

    @Schema(name = "outOrderNo", type = "String", description = "订单号", requiredMode = RequiredMode.REQUIRED)
    @NotBlank
    private String outOrderNo;

    @Schema(name = "outRefundNo", type = "String", description = "退款单号（渠道幂等键）", requiredMode = RequiredMode.REQUIRED)
    @NotBlank
    private String outRefundNo;
}
