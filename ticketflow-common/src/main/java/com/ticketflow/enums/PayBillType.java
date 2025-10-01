package com.ticketflow.enums;

/**
 * 支付账单类型枚举。区分不同业务产生的支付账单。
 * 当前仅支持: PROGRAM(节目购票)
 */
public enum PayBillType {
    /**
     * 账单类型
     * */
    PROGRAM(1,"节目"),
    ;

    private Integer code;

    private String msg;

    PayBillType(Integer code, String msg) {
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
        for (PayBillType re : PayBillType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static PayBillType getRc(Integer code) {
        for (PayBillType re : PayBillType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
