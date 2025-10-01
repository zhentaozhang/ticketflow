package com.ticketflow.enums;

/**
 * 对账状态枚举。定义订单与支付系统的对账结果状态。
 * 取值: 未对账 → 对账完成(有问题/无问题) → 问题已处理
 */
public enum ReconciliationStatus {
    /**
     * 对账状态
     * */
    RECONCILIATION_NO(1, "未对账"),
    RECONCILIATION_FAIL(-1,"对账完成有问题"),
    RECONCILIATION_SUCCESS(2,"对账完成没有问题"),
    RECONCILIATION_FINISH(3,"对账有问题处理完毕");
    
    private Integer code;
    
    private String msg = "";
    
    ReconciliationStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public Integer getCode() {
        return this.code;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (ReconciliationStatus re : ReconciliationStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static ReconciliationStatus getRc(Integer code) {
        for (ReconciliationStatus re : ReconciliationStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
