package com.ticketflow.enums;

/**
 * 节目订单版本。定义不同的下单接口版本(v1~v4)，
 * 用于灰度发布和接口升级兼容。
 * 取值: V1/V2/V21/V3/V31/V4/V41/V5
 */
public enum ProgramOrderVersion {
    /**
     * 版本
     *
     */
    V1_VERSION("v1", "v1版本", 1),

    V2_VERSION("v2", "v2版本", 2),

    V21_VERSION("v21", "v21版本", 21),

    V3_VERSION("v3", "v3版本", 3),

    V31_VERSION("v31", "v31版本", 31),

    V4_VERSION("v4", "v4版本", 4),

    V41_VERSION("v41", "v41版本", 41),

    V5_VERSION("v5", "v5版本", 5),
    ;

    private final String version;

    private final String msg;

    private final Integer value;

    ProgramOrderVersion(String version, String msg, Integer value) {
        this.version = version;
        this.msg = msg;
        this.value = value;
    }

    public String getVersion() {
        return version;
    }


    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public Integer getValue() {
        return value;
    }


    public static String getMsg(String version) {
        for (ProgramOrderVersion re : ProgramOrderVersion.values()) {
            if (re.version.equals(version)) {
                return re.msg;
            }
        }
        return "";
    }

    public static ProgramOrderVersion getRc(String version) {
        for (ProgramOrderVersion re : ProgramOrderVersion.values()) {
            if (re.version.equals(version)) {
                return re;
            }
        }
        return null;
    }
}
