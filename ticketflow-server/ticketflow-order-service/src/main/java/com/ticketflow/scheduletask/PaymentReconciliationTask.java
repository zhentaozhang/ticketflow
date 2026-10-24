package com.ticketflow.scheduletask;

import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.PaymentReconcileResult;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.service.OrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.PAYMENT_RECONCILE_TASK_LOCK;

/**
 * 支付对账定时任务：兜住"回调丢了但钱到了"的资损口子。
 * <p>
 * 背景：支付的实时链路只有"渠道回调"一条。而回调可能彻底丢（渠道发了、我们没收到，
 * 渠道的重试阶梯也跑完了），这时候会出现最坏的结果：<b>用户付了钱、订单却被超时取消</b>，
 * 而且没有任何东西会去发现它（库存那边有对账，支付这边一直没有）。
 * <p>
 * 路由：扫"最近取消、且还没做过支付对账"的订单，逐笔向渠道确认真实结果：
 * <ul>
 *   <li>渠道没收到钱 → 正常（取消的订单里绝大多数都是这样），标记对账完成；</li>
 *   <li>渠道收了钱 → 退款（唯一的补救方式，见 {@link OrderService#reconcilePayment}）；</li>
 *   <li>查渠道失败 → <b>不</b>标记对账完成，下一轮重试。</li>
 * </ul>
 * 所以每笔订单最多问渠道一次，不会反复打扰渠道接口。
 * <p>
 * <b>已知的规模局限</b>：这里是"逐单查渠道"。渠道查询接口有配额，且抢票场景下被取消的订单量很大
 * （大部分订单本来就不会支付），所以这套做法适合当前规模，不适合直接搬到大流量生产环境。
 * 生产上应该走"按时间段批量对账"（渠道对账文件 / 批量查询接口）——那时对账的输入是
 * 渠道的交易流水，而不是"我们的订单列表"，逐单查询只留给用户主动查询和投诉场景。
 */
@Slf4j
@Component
public class PaymentReconciliationTask {

    /**
     * 新鲜窗：取消不足 30 分钟的订单优先核对（离"钱可能正在到账"最近）。
     * <p>
     * 窗口本身不再是"职责边界"——窗口外的存量由 {@link #reconcileBacklogCancelledOrders()} 持续补扫，
     * 否则一笔订单只要在取消后 30 分钟内没被扫到，就永久没人再核对它。
     */
    private static final int SCAN_WINDOW_MINUTES = 30;

    /**
     * 「退款已受理、渠道还没确认到账」的订单重试窗口（24 小时）。
     * <p>
     * 这类订单已经脱离上面 30 分钟的「未对账」窗口（它们被标记成对账有问题），必须单独扫：
     * 否则会出现「系统显示已取消、钱却没退」的静默差异——用户没收到退款，系统里也没有任何东西再去找它。
     * 超过这个窗口仍没确认的，说明渠道一直没给出结果，只能叫人工，不要再无限打扰渠道接口。
     */
    private static final int REFUND_CONFIRM_WINDOW_MINUTES = 24 * 60;

    /**
     * 单轮最多核对多少笔。逐单查渠道是外部调用，必须限量，不能把一轮变成一次全量扫描。
     */
    private static final int BATCH_SIZE = 50;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 专用执行器：单线程 + 不排队（和 {@code ReconciliationTask} 同一套设计）。
     * 被跳过时记 {@code result=skipped} 指标，不静默；也不用业务线程池，避免尖峰期跑不起来。
     */
    private final ThreadPoolExecutor paymentReconcileExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "payment-reconcile-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> {
                log.warn("上一轮支付对账还在执行，本轮跳过");
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_SKIPPED);
            });

    @PreDestroy
    public void shutdown() {
        paymentReconcileExecutor.shutdown();
    }

    /**
     * 每 2 分钟一轮。窗口 30 分钟，所以一笔订单从取消到被核对，最多等 2 分钟。
     */
    @Scheduled(cron = "0 0/2 * * * ? ")
    public void paymentReconciliation() {
        paymentReconcileExecutor.execute(() -> {
            // 多实例互斥：这个任务会真的去问渠道，多实例同时跑等于把同一批订单问两遍（渠道接口有配额）。
            // 不等待（waitTime=0）：这轮已经被别的实例抢到了，我就跳过，不用等它跑完。
            RLock roundLock = serviceLockTool.getLock(LockType.Reentrant, PAYMENT_RECONCILE_TASK_LOCK,
                    new String[] {"all"});
            boolean locked = false;
            try {
                locked = roundLock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("其它实例正在执行支付对账，本轮跳过");
                    BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_SKIPPED);
                    return;
                }
                reconcile();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("支付对账任务获取互斥锁被中断", e);
            } catch (Exception e) {
                log.error("支付对账任务执行异常", e);
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_ERROR);
            } finally {
                if (locked) {
                    try {
                        roundLock.unlock();
                    } catch (Exception e) {
                        log.warn("释放支付对账互斥锁失败", e);
                    }
                }
            }
        });
    }

    private void reconcile() {
        // 新鲜窗优先：刚取消的订单离"钱可能正在到账"最近，晚一轮就是资损。
        boolean freshDrained = reconcileRecentCancelledOrders();
        // 新鲜窗排空了才往后推进存量。没排空还去啃存量，只会让新取消的订单排队更久。
        if (freshDrained) {
            reconcileBacklogCancelledOrders();
        }
        retryUnconfirmedRefunds();
    }

    /**
     * 第一段 A：最近取消、且还没做过支付对账的订单，逐笔向渠道确认真实结果。
     *
     * @return 新鲜窗是否已排空（本轮没扫满一批）。没排空说明系统正跟不上，本轮不再往后推进存量。
     */
    private boolean reconcileRecentCancelledOrders() {
        List<Order> candidates = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode())
                .eq(Order::getPayReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .ge(Order::getCancelOrderTime, DateUtils.addMinute(DateUtils.now(), -SCAN_WINDOW_MINUTES))
                .orderByAsc(Order::getCancelOrderTime)
                .last("limit " + BATCH_SIZE));
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return true;
        }
        processCandidates(candidates, "");
        return candidates.size() < BATCH_SIZE;
    }

    /**
     * 第一段 B：窗口外的存量（取消时间早于 30 分钟的未对账订单），每轮补一批。
     * <p>
     * <b>为什么必须有这一段</b>：只扫固定窗口等于「过了窗口就永久不再核对」。
     * 一笔订单若在取消后的 30 分钟内恰好没被扫到（渠道查不到、任务在重启、流量把批次挤满），
     * 就再也不会有人来核对它——钱收了、单取消了、没人发现，正好是最坏的那种差异。
     * <p>
     * 这段让候选集按「取消时间升序」持续被消费：核对出结果就标记对账完成、离开候选集，
     * 等价于一个不断向前推进的游标，且不需要额外存游标状态（进度就在订单表里）。
     * <p>
     * 代价要说清楚：一直核对失败（例如渠道长期不可用）的那批订单会占住队头，
     * 让存量推进停滞。这是刻意选择——它们正是最需要被反复核对的对象，
     * 与其静默跳过，不如积压暴露出来（见下面的 warn）。
     */
    private void reconcileBacklogCancelledOrders() {
        List<Order> candidates = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode())
                .eq(Order::getPayReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .lt(Order::getCancelOrderTime, DateUtils.addMinute(DateUtils.now(), -SCAN_WINDOW_MINUTES))
                .orderByAsc(Order::getCancelOrderTime)
                .last("limit " + BATCH_SIZE));
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return;
        }
        processCandidates(candidates, "backlog_");
        if (candidates.size() >= BATCH_SIZE) {
            log.warn("支付对账存量未排空：本轮又核对了一批 {} 笔窗口外的取消订单，仍有积压。"
                    + "请检查渠道查询是否持续失败，或下调单轮批量/缩短扫描周期", BATCH_SIZE);
        }
    }

    /**
     * 逐笔核对一批订单：查渠道 → 有结论的标记对账完成 → 计数打点。
     *
     * @param metricPrefix 指标前缀（存量批次用 {@code backlog_} 区分，免得看板上分不清是新鲜窗还是补扫）
     * @return 其中被判定「渠道已支付、已退款」的笔数
     */
    private int processCandidates(List<Order> candidates, String metricPrefix) {
        int refunded = 0;
        for (Order order : candidates) {
            try {
                PaymentReconcileResult result = orderService.reconcilePayment(order);
                if (result.isSettled()) {
                    orderService.markPaymentReconciled(order.getOrderNumber(),
                            ReconciliationStatus.RECONCILIATION_SUCCESS);
                }
                if (Objects.equals(result, PaymentReconcileResult.REFUNDED)) {
                    refunded++;
                }
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, metricPrefix + result.name().toLowerCase());
            } catch (Exception e) {
                // 单笔异常不影响后面的订单；不标记对账完成，下一轮会重试
                log.error("支付对账单笔核对异常 orderNumber : {}", order.getOrderNumber(), e);
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_ERROR);
            }
        }
        if (refunded > 0) {
            // 出现"渠道已支付但本地已取消"说明回调在丢，这是要叫人的信号
            log.warn("支付对账本轮发现 {} 笔“渠道已支付但本地已取消”的订单，已退款。回调可能存在丢失", refunded);
        }
        return refunded;
    }

    /**
     * 第二段：重试「退款已受理但渠道还没确认到账」的订单。
     * <p>
     * 顺序刻意是「先只查退款单状态，必要时才重新驱动渠道」：查询是幂等读操作，重新退款才是有资金含义的写操作。
     * 退款单号由订单号 + 金额推导（见 OrderService#reconfirmRefund），重试复用同一个幂等键，不会退第二次款。
     */
    private void retryUnconfirmedRefunds() {
        List<Order> candidates = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode())
                .eq(Order::getPayReconciliationStatus, ReconciliationStatus.RECONCILIATION_FAIL.getCode())
                .ge(Order::getCancelOrderTime, DateUtils.addMinute(DateUtils.now(), -REFUND_CONFIRM_WINDOW_MINUTES))
                .orderByAsc(Order::getCancelOrderTime)
                .last("limit " + BATCH_SIZE));
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            reportStaleUnconfirmedRefunds();
            return;
        }
        int confirmed = 0;
        for (Order order : candidates) {
            try {
                // 先只查退款单状态（无渠道写操作）：已确认到账的直接收尾
                if (orderService.reconfirmRefund(order)) {
                    orderService.markPaymentReconciled(order.getOrderNumber(),
                            ReconciliationStatus.RECONCILIATION_SUCCESS);
                    confirmed++;
                    BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_SUCCESS);
                    continue;
                }
                // 还没确认到账：必要时重新驱动一次渠道退款（幂等键保证不会退第二次款）
                PaymentReconcileResult result = orderService.reconcilePayment(order);
                if (Objects.equals(result, PaymentReconcileResult.REFUNDED)) {
                    orderService.markPaymentReconciled(order.getOrderNumber(),
                            ReconciliationStatus.RECONCILIATION_SUCCESS);
                    confirmed++;
                }
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, "refund_" + result.name().toLowerCase());
            } catch (Exception e) {
                log.error("退款确认重试单笔异常 orderNumber : {}", order.getOrderNumber(), e);
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_ERROR);
            }
        }
        if (confirmed > 0) {
            log.info("退款确认重试本轮确认到账 {} 笔，剩余 {} 笔仍待确认", confirmed, candidates.size() - confirmed);
        }
        reportStaleUnconfirmedRefunds();
    }

    /**
     * 超出重试窗口仍未确认到账的退款：不再自动重试，留一条告警日志等人去渠道侧查单。
     */
    private void reportStaleUnconfirmedRefunds() {
        try {
            Long staleCount = orderMapper.selectCount(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode())
                    .eq(Order::getPayReconciliationStatus, ReconciliationStatus.RECONCILIATION_FAIL.getCode())
                    .lt(Order::getCancelOrderTime,
                            DateUtils.addMinute(DateUtils.now(), -REFUND_CONFIRM_WINDOW_MINUTES)));
            if (Objects.nonNull(staleCount) && staleCount > 0) {
                log.error("有 {} 笔订单退款受理超过 {} 分钟仍未确认到账，已停止自动重试，需要人工核对渠道退款单",
                        staleCount, REFUND_CONFIRM_WINDOW_MINUTES);
                BusinessMetrics.increment(meterRegistry, Metrics.PAYMENT_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_UNCONFIRMED);
            }
        } catch (Exception e) {
            log.warn("统计超窗未确认退款失败", e);
        }
    }
}
