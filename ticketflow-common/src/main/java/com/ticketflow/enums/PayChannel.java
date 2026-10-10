package com.ticketflow.enums;

/**
 * 支付渠道枚举。支持的第三方支付平台。
 * 取值: ALIPAY(支付宝), WX(微信支付)
 */
public enum PayChannel {
    /**
     * 支付渠道
     * */
    ALIPAY(1,"alipay","支付宝"),
    
    WX(2,"wx","微信"),
    ;

    private Integer code;
    
    private String value;

    private String msg;

    PayChannel(Integer code, String value, String msg) {
        this.code = code;
        this.value = value;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
    
    public String getValue() {
        return value;
    }
    
    public void setValue(final String value) {
        this.value = value;
    }
    
    public static String getMsg(Integer code) {
        for (PayChannel re : PayChannel.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static PayChannel getRc(Integer code) {
        // code 为空时直接返回 null：这个方法会被“订单上可能没存渠道”的场景调到，
        // 不能在这里 NPE（原实现是 code.intValue()，传 null 会抛）
        if (code == null) {
            return null;
        }
        for (PayChannel re : PayChannel.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }

    /**
     * 按渠道值（如 alipay / wx）查枚举。
     * 前端传的是 value（见 OrderService#getPayDto 里的判断），而订单表里存的是 code。
     */
    public static PayChannel getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (PayChannel re : PayChannel.values()) {
            if (re.value.equals(value)) {
                return re;
            }
        }
        return null;
    }
}
