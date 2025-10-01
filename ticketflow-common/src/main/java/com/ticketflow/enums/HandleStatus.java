package com.ticketflow.enums;

/**
 * 处理状态枚举。通用的未处理/已处理二元状态。
 * 取值: NO_HANDLE(未处理), YES_HANDLE(已处理)
 */

public enum HandleStatus {
    /**
     * 处理状态
     * */
    NO_HANDLE(1,"未处理"),
    YES_HANDLE(2,"已处理")
    ;

    private Integer code;

    private String msg;

    HandleStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (HandleStatus re : HandleStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static HandleStatus getRc(Integer code) {
        for (HandleStatus re : HandleStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
