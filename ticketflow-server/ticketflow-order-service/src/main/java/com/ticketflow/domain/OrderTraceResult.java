package com.ticketflow.domain;

import lombok.Data;

import java.util.Date;

/**
 * 单笔订单的排查视图。
 * <p>
 * 为什么要它：一笔订单在链路上会经过 Redis、Kafka、MySQL 好几处记录，
 * 之前排查只能"拿订单号挨个地方翻"（Redis 扣减流水 / 消息 / DB 订单 / 丢弃队列 / 待裁决队列）。
 * 这个接口把这几处一次性拼出来，并给一句"这一单停在哪一步"的结论。
 */
@Data
public class OrderTraceResult {

    private Long orderNumber;

    /** MySQL 里有没有这个订单（状态是最终事实） */
    private Boolean orderExists;

    /** 订单状态：1 未支付 / 2 已取消 / 3 已支付 / 4 已退单 */
    private Integer orderStatus;

    /** 支付对账状态（见 ReconciliationStatus）：1 未对账 / 2 对账完成 */
    private Integer payReconciliationStatus;

    private Long programId;

    private Long userId;

    private Long identifierId;

    private Date createOrderTime;

    private Date cancelOrderTime;

    private Date payOrderTime;

    /** 建单完成标记是否存在（前端就是靠它轮询到终态的；它是 Redis 数据，丢了就永远轮询不到） */
    private Boolean createMarkPresent;

    /** Redis 扣减流水的 field（对账锚点） */
    private String deductRecordField;

    /** Redis 扣减流水是否存在（"这次扣减确实发生过"的唯一书面证据） */
    private Boolean deductRecordPresent;

    /** 是否在丢弃队列里（建单被主动丢弃 / 重试穷尽） */
    private Boolean discarded;

    /** 是否在待裁决队列里（Kafka 发送结果未知） */
    private Boolean pending;

    /** 一句话结论：这一单现在停在哪一步 */
    private String hint;
}
