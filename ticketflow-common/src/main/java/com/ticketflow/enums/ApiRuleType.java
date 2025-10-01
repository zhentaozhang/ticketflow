package com.ticketflow.enums;

/**
 * API限流规则类型。定义接口适用的限流策略类型。
 * 取值: NO_RULE(无规则), RULE(普通规则), DEPTH_RULE(深度规则)
 */
public enum ApiRuleType {
    /**
     * 没有规则
     * */
    NO_RULE(0,"没有规则"),
    /**
     * 普通规则
     * */
    RULE(1,"普通规则"),
    /**
     * 深度规则
     * */
    DEPTH_RULE(2,"深度规则"),
    ;

    private Integer code;

    private String msg;

    ApiRuleType(Integer code, String msg) {
        this.code = code;
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

    public static String getMsg(Integer code) {
        for (ApiRuleType re : ApiRuleType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static ApiRuleType getRc(Integer code) {
        for (ApiRuleType re : ApiRuleType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
