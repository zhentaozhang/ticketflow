package com.ticketflow.enums;

/**
 * 单笔支付对账的结果。用于定时对账任务分流处理（更新对账状态 + 打指标）。
 */
public enum PaymentReconcileResult {

    /**
     * 这笔从没发起过支付（订单上没有渠道），没有可对的内容
     */
    NO_CHANNEL,

    /**
     * 渠道没收到钱：正常情况（取消的订单里绝大多数都是这样），无需处理
     */
    NOT_PAID,

    /**
     * 渠道收了钱、本地订单已取消 → 已退款成功
     */
    REFUNDED,

    /**
     * 渠道收了钱，但退款失败：需要下一轮重试 / 人工介入
     */
    REFUND_FAILED,

    /**
     * 查渠道这一步就失败了（渠道不可用、超时）：下一轮重试
     */
    CHECK_FAILED;

    /**
     * 是否已经处理完毕（可以标记为“对账完成”，不必再核对）
     */
    public boolean isSettled() {
        return this == NO_CHANNEL || this == NOT_PAID || this == REFUNDED;
    }
}
