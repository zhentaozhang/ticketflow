package com.ticketflow.enums;

import lombok.Getter;

/**
 * 消息消费状态。定义可靠消息中消费者消息的处理状态。
 * 取值: UNCONSUMED(未消费) → CONSUMER_SUCCESS(成功) / CONSUMER_FAIL(失败)
 */
@Getter
public enum MessageConsumerStatus {
    /**
     * 消息消费状态枚举
     * */
    UNCONSUMED(1,"未消费"),
    CONSUMER_FAIL(-1,"消费失败"),
    CONSUMER_SUCCESS(2,"消费成功"),
    ;

    private final Integer code;

    private final String msg;

    MessageConsumerStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public static String getMsg(Integer code) {
        if (code == null) {
            return "";
        }
        for (MessageConsumerStatus re : MessageConsumerStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static MessageConsumerStatus getRc(Integer code) {
        for (MessageConsumerStatus re : MessageConsumerStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
