package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 退款 dto
 */
@Data
@Schema(title="RefundDto", description ="退款")
public class RefundDto {
    
    @Schema(name ="orderNumber", type ="Long", description ="订单号",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String orderNumber;
    
    @Schema(name ="amount", type ="BigDecimal", description ="退款金额",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private BigDecimal amount;
    
    @Schema(name ="channel", type ="Integer", description ="退款渠道 alipay：支付宝 wx：微信",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private String channel;
    
    @Schema(name ="reason", type ="String", description ="退款原因")
    private String reason;

    /**
     * 退款请求幂等键（可选但强烈建议提供）。
     * 作为渠道侧退款单号（outRefundNo）与 d_refund_bill.out_refund_no 唯一键：
     * 同一 refundRequestId 的重试（超时/消息重投）不会产生第二笔退款。
     * 为空时退化为每次生成新单号（旧行为，不幂等）。
     */
    @Schema(name ="refundRequestId", type ="String", description ="退款请求幂等键")
    private String refundRequestId;
}
