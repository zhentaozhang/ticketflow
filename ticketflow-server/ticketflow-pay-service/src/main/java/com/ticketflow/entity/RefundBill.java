package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 退款账单。记录每笔退款的流水信息，关联原支付账单(PayBill)，
 * 包含退款金额、状态、原因和时间。
 * 数据表: d_refund_bill
 */
@Data
@TableName("d_refund_bill")
public class RefundBill extends BaseTableData {

    /**
     * 主键id
     */
    private Long id;

    /**
     * 商户订单号
     */
    private String outOrderNo;

    /**
     * 账单id
     */
    private Long payBillId;

    /**
     * 退款单号（渠道侧幂等键、退款状态查询依据），唯一
     */
    private String outRefundNo;

    /**
     * 退款金额
     */
    private BigDecimal refundAmount;

    /**
     * 账单退款状态 1：退款处理中 2：已退款 3：退款失败
     */
    private Integer refundStatus;

    /**
     * 退款时间
     */
    private Date refundTime;

    /**
     * 退款原因
     *
     */
    private String reason;
}
