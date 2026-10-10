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
     * 只核对这个时间窗内被取消的订单。再早的靠人工/离线对账，不在在线任务的职责范围内。
     */
    private static final int SCAN_WINDOW_MINUTES = 30;

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
        List<Order> candidates = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode())
                .eq(Order::getPayReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .ge(Order::getCancelOrderTime, DateUtils.addMinute(DateUtils.now(), -SCAN_WINDOW_MINUTES))
                .orderByAsc(Order::getCancelOrderTime)
                .last("limit " + BATCH_SIZE));
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return;
    }
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
                    Metrics.RESULT, result.name().toLowerCase());
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
    }
}
