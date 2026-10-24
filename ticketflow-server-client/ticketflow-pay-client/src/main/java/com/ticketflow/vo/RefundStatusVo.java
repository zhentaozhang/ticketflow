package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 退款单状态 vo。
 *
 * <p>refundStatus 取值见 {@link com.ticketflow.enums.RefundStatus}：
 * 0 受理待确认 / 1 处理中 / 2 已退款 / 3 退款失败。
 */
@Data
@Schema(title = "RefundStatusVo", description = "退款单状态")
public class RefundStatusVo {

    @Schema(name = "outOrderNo", type = "String", description = "订单号")
    private String outOrderNo;

    @Schema(name = "outRefundNo", type = "String", description = "退款单号")
    private String outRefundNo;

    @Schema(name = "refundStatus", type = "Integer", description = "退款状态 0:受理待确认 1:处理中 2:已退款 3:退款失败")
    private Integer refundStatus;

    @Schema(name = "refundTime", type = "Date", description = "退款时间")
    private Date refundTime;
}
