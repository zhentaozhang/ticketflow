package com.couponseckill.common;

import lombok.Getter;

/**
 * 统一错误码（对齐设计文档 §9.1）。
 */
@Getter
public enum ErrorCode {

    // ---- 通用 ----
    SUCCESS(200, "成功"),
    BAD_REQUEST(40001, "参数错误"),
    UNAUTHORIZED(40100, "未登录"),
    INTERNAL_ERROR(50000, "系统繁忙，请稍后重试"),

    // ---- 秒杀抢购 ----
    ACTIVITY_NOT_FOUND(50001, "活动不存在"),
    ACTIVITY_NOT_STARTED(50010, "活动未开始"),
    ACTIVITY_ENDED(50011, "活动已结束"),
    STOCK_EMPTY(50012, "手慢了，优惠券已被抢光"),
    OVER_LIMIT(50013, "超出限购数量"),
    DUPLICATE_REQUEST(50014, "请勿重复提交"),
    ACTIVITY_OFFLINE(50015, "活动已下架"),
    SYSTEM_BUSY(50020, "系统繁忙，请稍后重试"),

    // ---- 发券/落库 ----
    COUPON_ISSUE_FAILED(50100, "发券失败"),

    // ---- 用券 ----
    COUPON_NOT_FOUND(50201, "优惠券不存在"),
    COUPON_NOT_AVAILABLE(50202, "优惠券不可用"),
    COUPON_ALREADY_LOCKED(50203, "优惠券已被占用"),
    COUPON_LOCK_FAILED(50204, "优惠券锁定失败"),
    COUPON_ORDER_MISMATCH(50205, "优惠券与订单不匹配");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
