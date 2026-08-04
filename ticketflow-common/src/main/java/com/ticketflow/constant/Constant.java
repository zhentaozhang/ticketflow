package com.ticketflow.constant;

import com.ticketflow.enums.RecordType;

/**
 * 全局常量定义。
 * <p>
 * 关键常量说明：
 * TRACE_ID / SKY_WALKING_TRACE_ID — 链路追踪 ID
 * GRAY_PARAMETER / GRAY_FLAG_*   — 灰度标记
 * USER_ID / CODE                 — 链路传播中的 Header 名
 * SPRING_INJECT_PREFIX_DISTINCTION_NAME — Feign 服务名的动态前缀占位符
 * SERVER_GRAY                    — Nacos 元数据灰度标记占位符
 */
public class Constant {

    /**
     * 链路id
     *
     */
    public static final String TRACE_ID = "traceId";

    public static final String GRAY_FLAG_TRUE = "true";

    public static final String GRAY_FLAG_FALSE = "false";

    public static final String GRAY_PARAMETER = "gray";

    public static final String CODE = "code";

    public static final String USER_ID = "userId";

    public static final String GLIDE_LINE = "_";

    public static final String ALIPAY_NOTIFY_SUCCESS_RESULT = "success";

    public static final String ALIPAY_NOTIFY_FAILURE_RESULT = "failure";

    public static final String WX_NOTIFY_SUCCESS_RESULT = "SUCCESS";

    public static final String WX_NOTIFY_FAILURE_RESULT = "FAIL";

    public static final String PREFIX_DISTINCTION_NAME = "prefix.distinction.name";

    public static final String DEFAULT_PREFIX_DISTINCTION_NAME = "ticketflow";

    public static final String SPRING_INJECT_PREFIX_DISTINCTION_NAME = "${" + PREFIX_DISTINCTION_NAME + ":" + DEFAULT_PREFIX_DISTINCTION_NAME + "}";

    public static final String SERVER_GRAY = "${spring.cloud.nacos.discovery.metadata.gray:false}";

    public static final String REDUCE = RecordType.REDUCE.getValue();

    public static final String CHANGE_STATUS = RecordType.CHANGE_STATUS.getValue();

    public static final String INCREASE = RecordType.INCREASE.getValue();

    public static final String SKY_WALKING_TRACE_ID = "skyWalkingTraceId";

}
