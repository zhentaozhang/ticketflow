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

    /** 订单创建失败总数（消费超时丢弃 / 建单异常），tags：{@link #REASON}。
     *  （不携带 programId：无界标签会撑爆 Prometheus 时间线；按节目定位从 DISCARD_ORDER 记录取） */
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
    /** 下单版本 tag key（V4） */
    public static final String VERSION = "version";
    public static final String VERSION_V4 = "V4";
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

    /* ==================== 节目数据同步（order-service，事务外 outbox，每分钟） ==================== */

    /**
     * 支付/取消后"把节目服务 DB 收敛到目标状态"的结果总数，tags：{@link #RESULT}。
     * 取值：
     * <ul>
     *   <li>{@link #RESULT_SUCCESS}：首次推送就成功；</li>
     *   <li>{@link #RESULT_VERIFIED}：推送返回失败/超时，但复核发现 DB 其实已生效（响应丢失），不重复推送；</li>
     *   <li>{@link #RESULT_RETRY}：本次没收敛，已排下一轮重试；</li>
     *   <li>{@link #RESULT_NOT_CONVERGED}：Redis（权威）还没收敛，拒绝推进 DB，退避重试；</li>
     *   <li>{@link #RESULT_DEAD}：重试次数用尽，转人工——<b>这是要叫人的信号</b>；</li>
     *   <li>{@link #RESULT_UNSYNCED}：差异扫描发现"终态订单却没有同步登记"，同样需要人工确认；</li>
     *   <li>{@link #RESULT_ERROR}：同步过程抛异常。</li>
     * </ul>
     */
    public static final String ORDER_PROGRAM_SYNC_TOTAL = "ticketflow_order_program_sync_total";
    /** 推送返回失败但复核发现已生效（响应丢失，不重复推送） */
    public static final String RESULT_VERIFIED = "verified";
    /** 未收敛，已排下一轮重试 */
    public static final String RESULT_RETRY = "retry";
    /** Redis 权威数据还没收敛，拒绝把 DB 推到前面 */
    public static final String RESULT_NOT_CONVERGED = "not_converged";
    /** 重试次数用尽，转人工 */
    public static final String RESULT_DEAD = "dead";
    /** 差异扫描发现终态订单未登记节目数据同步 */
    public static final String RESULT_UNSYNCED = "unsynced";

    /* ==================== 退款收尾（order-service，渠道退款成功后） ==================== */

    /**
     * 「渠道已付款、本地订单已取消」的退款在本地收尾的结果总数，tags：{@link #RESULT}。
     * 取值：
     * <ul>
     *   <li>{@link #RESULT_SUCCESS}：主表置已退单（带退款时间）+ 购票人子表 + 对账标记一次事务写完；</li>
     *   <li>{@link #RESULT_IDEMPOTENT}：重复回调/对账重试，本地已是已退单；</li>
     *   <li>{@link #RESULT_ERROR}：<b>钱已退、本地状态没推进</b>，要叫人——限购额度不会释放、资金侧无退款流水。</li>
     * </ul>
     */
    public static final String ORDER_REFUND_SETTLE_TOTAL = "ticketflow_order_refund_settle_total";
    /** 重复调用命中幂等（本地已是终态） */
    public static final String RESULT_IDEMPOTENT = "idempotent";
    /**
     * 退款已受理但<b>渠道还没确认到账</b>（退款单状态 0/1/3），订单保持已取消等待对账重试。
     * 这一类不能再靠「退款请求返回成功」推断成已退单——钱可能没到买家手上。
     */
    public static final String RESULT_UNCONFIRMED = "unconfirmed";

    /* ==================== 退款受理与确认（pay-service） ==================== */

    /**
     * 退款受理结果总数（{@code PayService#refund}），tags：{@link #RESULT}。
     * 取值：
     * <ul>
     *   <li>{@link #RESULT_ACCEPTED}：本次首次受理，已落「受理待确认」退款单后调渠道；</li>
     *   <li>{@link #RESULT_SUCCESS}：渠道同步确认已退款（refund_status=2）；</li>
     *   <li>{@link #RESULT_PROCESSING}：渠道受理，退款处理中（refund_status=1），由确认任务跟进；</li>
     *   <li>{@link #RESULT_UNKNOWN}：<b>钱可能已经退出去了但结果未知</b>（refund_status=0），必须靠确认任务查渠道；</li>
     *   <li>{@link #RESULT_FAIL}：渠道明确失败/关闭（refund_status=3），可用同一幂等键重入；</li>
     *   <li>{@link #RESULT_IDEMPOTENT}：同一幂等键重试命中已有退款单。</li>
     * </ul>
     */
    public static final String PAY_REFUND_TOTAL = "ticketflow_pay_refund_total";
    /** 首次受理（已落受理单） */
    public static final String RESULT_ACCEPTED = "accepted";
    /** 渠道受理中（refund_status=1） */
    public static final String RESULT_PROCESSING = "processing";

    /**
     * 退款确认任务单条结果总数（{@code RefundCheckTask}），tags：{@link #RESULT}。
     * 取值：{@link #RESULT_SUCCESS} / {@link #RESULT_PROCESSING} / {@link #RESULT_FAIL} / {@link #RESULT_ERROR} / {@link #RESULT_UNKNOWN}。
     * <p>
     * 其中 {@code fail}/{@code unknown} 是<b>要叫人的信号</b>：钱退没退成必须有人工出口，
     * 不能只留一行日志。
     */
    public static final String PAY_REFUND_CHECK_TOTAL = "ticketflow_pay_refund_check_total";

    /* ==================== 未支付账单主动查单（pay-service，每 2 分钟） ==================== */

    /**
     * 「账单还是未支付」时主动问渠道的结果总数（{@code PayBillReconcileTask}），tags：{@link #RESULT}。
     * <p>
     * 背景：支付成功的实时链路只有「渠道回调」一条。回调丢了以后，账单会永远停在未支付，
     * 用户却已经付钱——只能靠主动查单发现并补状态。
     * 取值：
     * <ul>
     *   <li>{@link #RESULT_PAID}：渠道确认已支付、账单已补成已支付，<b>这个数字就是「回调丢了」的量</b>；</li>
     *   <li>{@link #RESULT_CLOSED}：渠道侧交易已关闭（超时未支付），本地账单同步置已取消；</li>
     *   <li>{@link #RESULT_UNPAID}：渠道侧仍是待支付，正常；</li>
     *   <li>{@link #RESULT_QUERY_FAILED}：查渠道失败/查不到单，下轮再查；</li>
     *   <li>{@link #RESULT_AMOUNT_MISMATCH}：渠道已支付但金额与账单不一致，<b>要叫人</b>
     *       （不能改状态：金额是后续退款与对账的依据）；</li>
     *   <li>{@link #RESULT_CONCURRENT}：条件更新 0 行，已被回调或其它实例先改掉；</li>
     *   <li>{@link #RESULT_SKIPPED} / {@link #RESULT_ERROR}。</li>
     * </ul>
     */
    public static final String PAY_BILL_RECONCILE_TOTAL = "ticketflow_pay_bill_reconcile_total";
    /** 渠道确认已支付、本地账单补正为已支付（回调丢失的证据） */
    public static final String RESULT_PAID = "paid";
    /** 渠道侧交易已关闭，账单同步置已取消 */
    public static final String RESULT_CLOSED = "closed";
    /** 渠道侧仍是待支付（正常情况下就是这个） */
    public static final String RESULT_UNPAID = "unpaid";
    /** 查渠道失败/查不到单 */
    public static final String RESULT_QUERY_FAILED = "query_failed";
    /** 渠道已支付但金额与本地账单不一致（不能自动改状态） */
    public static final String RESULT_AMOUNT_MISMATCH = "amount_mismatch";
    /** 条件更新 0 行，已被回调/其它实例抢先 */
    public static final String RESULT_CONCURRENT = "concurrent";
}
