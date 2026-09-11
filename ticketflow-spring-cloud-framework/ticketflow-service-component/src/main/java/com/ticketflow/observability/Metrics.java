package com.ticketflow.observability;

/**
 * 业务指标词典。
 *
 * <p>命名规范：{@code ticketflow_<模块>_<事件>[_<单位>]}，单位使用基础单位
 * （计数 {@code _total}、时长 {@code _seconds}），与 Prometheus 约定一致。
 *
 * <p>指标清单与告警规则、Grafana 面板联动维护，见
 * {@code docs/observability/02-指标词典与告警设计.md}。
 */
public final class Metrics {

    private Metrics() {
    }

    /* ==================== 订单创建（order-service） ==================== */

    /** 订单创建失败总数（消费超时丢弃 / 建单异常），tags：{@link #REASON} / {@link #PROGRAM_ID} */
    public static final String ORDER_CREATE_FAIL_TOTAL = "ticketflow_order_create_fail_total";

    /**
     * 建单成功（真的落库了）的数量，tags：{@link #VERSION}。
     * <p>
     * 它是<b>端到端落库率的分子</b>：
     * {@code sum(rate(ticketflow_order_created_total[5m])) / sum(rate(ticketflow_program_stock_deduct_total{result="success"}[5m]))}。
     * 异步架构下“受理成功 ≠ 订单落库成功”，只看受理成功率会得到一个好看但不可信的数字（见 12）。
     */
    public static final String ORDER_CREATED_TOTAL = "ticketflow_order_created_total";

    /**
     * 建单消息从下单到被消费处理所经过的时长（秒），tags：{@link #VERSION}。
     * <p>
     * 它比“积压条数”更贴近业务：消费者会因为“消息延迟超过 60 秒”主动丢弃（那时用户已经不等了），
     * 所以这个值的 p99 直接反映“离丢弃还有多远”。
     */
    public static final String ORDER_CREATE_DELAY_SECONDS = "ticketflow_order_create_delay_seconds";
    /** 失败原因 tag key */
    public static final String REASON = "reason";
    /** 消费延迟超过阈值被丢弃（V4） */
    public static final String REASON_CREATE_ORDER_DELAY = "CREATE_ORDER_DELAY";
    /** 消费建单异常 */
    public static final String REASON_CREATE_ORDER_FAIL = "CREATE_ORDER_FAIL";
    /** 节目 id tag key */
    public static final String PROGRAM_ID = "programId";

    /* ==================== 库存扣减（program-service，Lua 原子扣减） ==================== */

    /** Lua 扣减调用结果分桶总数，tags：{@link #VERSION} / {@link #RESULT} */
    public static final String STOCK_DEDUCT_TOTAL = "ticketflow_program_stock_deduct_total";
    /** Lua 扣减耗时分布（Timer，单位秒），tags：{@link #VERSION} / {@link #RESULT} */
    public static final String STOCK_DEDUCT_DURATION_SECONDS = "ticketflow_program_stock_deduct_duration_seconds";
    /** 下单版本 tag key（V4/V5） */
    public static final String VERSION = "version";
    public static final String VERSION_V4 = "V4";
    public static final String VERSION_V5 = "V5";
    /** 结果 tag key */
    public static final String RESULT = "result";
    /** 扣减成功（Lua code=0） */
    public static final String RESULT_SUCCESS = "success";
    /** 业务拒绝：座位/余票/价格校验失败（code 40001~40011 等） */
    public static final String RESULT_FAIL = "fail";
    /** 限购/重复提交（code 40035） */
    public static final String RESULT_LIMIT = "limit";
    /** 脚本执行异常 */
    public static final String RESULT_ERROR = "error";
    /** 返回体缺失/未知错误码 */
    public static final String RESULT_UNKNOWN = "unknown";

    /* ==================== 库存守恒（order-service 对账兜底） ==================== */

    /** V5 Redis↔DB 库存守恒异常次数，tags：{@link #PROGRAM_ID} */
    public static final String V5_STOCK_CONSISTENCY_VIOLATION = "ticketflow_v5_stock_consistency_violation";

    /* ==================== 对账任务（order-service，每分钟） ==================== */

    /** 对账任务执行结果总数，tags：{@link #RESULT}（success/empty/error/skipped） */
    public static final String RECONCILIATION_TASK_TOTAL = "ticketflow_reconciliation_task_total";
    /** 对账任务单轮补偿节目数（每个 programId 一次补偿流程计 1） */
    public static final String RECONCILIATION_COMPENSATE_TOTAL = "ticketflow_reconciliation_compensate_total";

    /* ==================== 支付对账（order-service，每 2 分钟） ==================== */

    /**
     * 支付对账结果总数，tags：{@link #RESULT}。
     * 取值：no_channel / not_paid / refunded / refund_failed / check_failed / skipped / error。
     * <p>
     * 其中 <b>refunded 是资损信号</b>：出现它说明“渠道收了钱、回调却丢了、订单已被取消”，
     * 靠主动查渠道才发现并退的款。
     */
    public static final String PAYMENT_RECONCILE_TOTAL = "ticketflow_payment_reconcile_total";
    /** 对账无数据（健康空跑） */
    public static final String RESULT_EMPTY = "empty";
    /**
     * 本轮被跳过：上一轮还在跑（单线程 + 不排队），或提交被拒绝。
     * 跳过本身不丢数据（下轮扫的是同一个时间窗口），连续多轮跳过才说明单轮对账变慢了，需要告警。
     */
    public static final String RESULT_SKIPPED = "skipped";
}
