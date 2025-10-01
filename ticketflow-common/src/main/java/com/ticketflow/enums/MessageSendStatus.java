package com.ticketflow.enums;

import lombok.Getter;

/**
 * 消息发送状态。定义可靠消息中生产者消息的发送状态。
 * 取值: UNSENT(未发送) → SEND_SUCCESS(成功) / SEND_FAIL(失败)
 */
@Getter
public enum MessageSendStatus {
    /**
     * 消息发送状态枚举
     * */
    UNSENT(1,"未发送"),
    SEND_FAIL(-1,"发送失败"),
    SEND_SUCCESS(2,"发送成功"),
    ;

    private final Integer code;

    private final String msg;

    MessageSendStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public static String getMsg(Integer code) {
        if (code == null) {
            return "";
        }
        for (MessageSendStatus re : MessageSendStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static MessageSendStatus getRc(Integer code) {
        for (MessageSendStatus re : MessageSendStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
