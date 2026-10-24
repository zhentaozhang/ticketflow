package com.ticketflow.domain;

import lombok.Data;

/**
 * outbox 扫描出来的一条待同步记录（只有扫描需要的三个字段）。
 */
@Data
public class OrderProgramSyncRecord {

    /** 订单号（同时是 outbox 行的分片键） */
    private Long orderNumber;

    /** 已经尝试过的次数，决定下一轮的退避时间与是否转死信 */
    private Integer attempts;

    /** {@link ProgramSyncPayload} 的 JSON */
    private String payload;
}
