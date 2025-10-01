package com.ticketflow.enums;

/**
 * 限流规则状态。RUN(正常)/STOP(禁用)，控制规则是否生效。
 */
public enum RuleStatus {
    /**
     * 规则状态
     * */
    RUN(1,"正常"),
    STOP(0,"禁用")
    ;

    private Integer code;

    private String msg;

    RuleStatus(Integer code, String msg) {
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
        for (RuleStatus re : RuleStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static RuleStatus getRc(Integer code) {
        for (RuleStatus re : RuleStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
