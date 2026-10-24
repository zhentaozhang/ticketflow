package com.ticketflow.enums;

/**
 * 退款单状态。
 *
 * <p>为什么要在"处理中/成功/失败"之外再造一个 0（受理待确认）：
 * 渠道退款调用可能已经发出去了，但我们没拿到结果（超时、进程崩溃、落库失败）。
 * 这种"钱可能已经动了、我们却不知道结果"的状态必须与"还没开始退款"、
 * "渠道明确说失败"区分开——它既不能当成功把订单置成已退单（钱可能没到账），
 * 也不能当失败直接放弃（钱可能已经退给用户）。0 态的唯一出口是<b>主动查渠道</b>确认。
 */
public enum RefundStatus {

    /**
     * 已受理，渠道结果未知（调用已发出但没拿到结果）
     */
    REFUND_ACCEPT(0, "退款受理待确认"),

    /**
     * 渠道已受理，退款处理中
     */
    REFUND_PROCESSING(1, "退款处理中"),

    /**
     * 渠道确认退款成功（钱已退给用户）
     */
    REFUND_SUCCESS(2, "已退款"),

    /**
     * 渠道明确退款失败/关闭（未退款）
     */
    REFUND_FAIL(3, "退款失败");

    private final Integer code;

    private final String msg;

    RefundStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static RefundStatus getRc(Integer code) {
        if (code == null) {
            return null;
        }
        for (RefundStatus status : values()) {
            if (status.code.intValue() == code.intValue()) {
                return status;
            }
        }
        return null;
    }
}
