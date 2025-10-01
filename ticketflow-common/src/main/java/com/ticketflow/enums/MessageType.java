package com.ticketflow.enums;

import lombok.Getter;

/**
 * 消息类型枚举。定义可靠消息系统中的消息分类。
 * 当前仅支持: DELAY_ORDER_CANCEL(延迟订单取消)
 */
@Getter
public enum MessageType {
    /**
     * 消息类型枚举
     * */
    DELAY_ORDER_CANCEL(1,"延迟订单取消"),
    ;

    private final Integer code;

    private final String msg;

    MessageType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public static String getMsg(Integer code) {
        if (code == null) {
            return "";
        }
        for (MessageType re : MessageType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static MessageType getRc(Integer code) {
        for (MessageType re : MessageType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
