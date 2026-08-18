package com.couponseckill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抢购结果（异步发券，结果可能为"处理中"，由客户端轮询查询接口）。
 */
@Data
public class GrabResult {

    /** PROCESSING=排队中 SUCCESS=已抢到 FAIL=失败 NONE=未参与 */
    private String grabStatus;

    private String orderNo;

    private String message;

    private String couponNo;

    private BigDecimal amount;

    private BigDecimal minAmount;

    private LocalDateTime validStart;

    private LocalDateTime validEnd;

    public static GrabResult processing(String orderNo) {
        GrabResult r = new GrabResult();
        r.grabStatus = "PROCESSING";
        r.orderNo = orderNo;
        r.message = "抢购排队中，请稍后查询结果";
        return r;
    }

    public static GrabResult none() {
        GrabResult r = new GrabResult();
        r.grabStatus = "NONE";
        r.message = "未参与该活动";
        return r;
    }

    public static GrabResult fail(String message) {
        GrabResult r = new GrabResult();
        r.grabStatus = "FAIL";
        r.message = message;
        return r;
    }
}
