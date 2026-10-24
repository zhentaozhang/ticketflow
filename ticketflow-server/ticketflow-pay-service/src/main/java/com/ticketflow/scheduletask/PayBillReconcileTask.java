package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.entity.PayBill;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.TradeResult;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.PAY_BILL_RECONCILE_TASK_LOCK;

/**
 * 未支付账单主动查单任务：补上「回调丢了、账单永远停在未支付」这个口子。
 * <p>
 * 背景：账单从「未支付」翻成「已支付」原来只有一条路——渠道异步回调。
 * 回调彻底丢了以后（渠道发过、我们没收到，渠道的重试也跑完了），本地账单会永远停在未支付：
 * 用户钱付了，我们既不发凭证也不入账，而且<b>没有任何东西会去发现它</b>。
 * 订单侧虽然有超时关单与支付对账，但它们的输入是「渠道说这单付了没」，
 * 账单状态本身没人负责收敛。
 * <p>
 * 做法：扫「未支付且已经等了足够久」的账单，逐笔向渠道查单——
 * <ul>
 *   <li>渠道说已支付、金额一致 → 条件更新补成已支付（这一步等于替回调补课）；</li>
 *   <li>渠道说交易已关闭（超时未支付被关单） → 本地置已取消，让它离开候选集；</li>
 *   <li>渠道说仍然待支付 → 什么都不做，下一轮再看；</li>
 *   <li>查渠道失败/查不到单 → 不改状态，下一轮重试。</li>
 * </ul>
 * <p>
 * <b>为什么金额不一致宁可不动</b>：账单金额是后续退款与对账的唯一依据，
 * 拿一个对不上的金额改状态，等于把资损藏进状态里；这种情况只能叫人工。
 * <p>
 * <b>规模说明</b>：这是逐单查渠道，渠道查询接口有配额；这里按 {@code pay_time} 升序限量推进，
 * 并且只回溯 {@value #LOOKBACK_HOURS} 小时——更早的账单渠道侧可能已不可查，
 * 生产上应改用渠道对账文件（离线对账），逐单查只留给用户主动查询与投诉场景。
 */
@Slf4j
@Component
public class PayBillReconcileTask {

    /**
     * 账单至少「晾」这么久才去查渠道：刚拉起支付的账单，回调大概率还在路上，别抢它的活。
     */
    private static final int NOT_BEFORE_MINUTES = 2;

    /**
     * 回溯上限：更早的未支付账单不再逐单查（渠道侧通常已不可查，交给渠道对账文件/人工）。
     */
    private static final int LOOKBACK_HOURS = 48;

    /**
     * 单轮最多查多少笔。逐单查渠道是外部调用，必须限量。
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 渠道查询连续失败多少笔就早退：这类失败通常是渠道整体不可用，
     * 继续把 100 笔挨个打过去没有意义，还会把渠道的可用性进一步打垮。
     */
    private static final int MAX_CONSECUTIVE_QUERY_FAILED = 5;

    @Autowired
    private PayBillMapper payBillMapper;

    @Autowired
    private PayStrategyContext payStrategyContext;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 主动查单指标（registry 缺失时 {@link BusinessMetrics} 静默降级，不影响资金主流程）
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 专用执行器：单线程 + 不排队。单轮跑不完就跳过下一轮（下轮扫的是同一批未支付账单，不会丢数据）。
     */
    private final ThreadPoolExecutor payBillReconcileExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "pay-bill-reconcile-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> {
                log.warn("上一轮支付账单主动查单还在执行，本轮跳过");
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_SKIPPED);
            });

    @PreDestroy
    public void shutdown() {
        payBillReconcileExecutor.shutdown();
    }

    /**
     * 每 2 分钟一轮，刻意错开整点（退款确认任务在 :00/:05 起跑），避免两个查渠道的任务同时打渠道。
     */
    @Scheduled(cron = "0 1/2 * * * ? ")
    public void payBillReconciliation() {
        payBillReconcileExecutor.execute(() -> {
            RLock roundLock = serviceLockTool.getLock(LockType.Reentrant, PAY_BILL_RECONCILE_TASK_LOCK,
                    new String[]{"all"});
            boolean locked = false;
            try {
                locked = roundLock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("其它实例正在执行支付账单主动查单，本轮跳过");
                    BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_SKIPPED);
                    return;
                }
                reconcileNoPayBills();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("支付账单主动查单任务获取互斥锁被中断", e);
            } catch (Exception e) {
                log.error("支付账单主动查单任务执行异常", e);
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_ERROR);
            } finally {
                if (locked) {
                    try {
                        roundLock.unlock();
                    } catch (Exception e) {
                        log.warn("释放支付账单主动查单互斥锁失败", e);
                    }
                }
            }
        });
    }

    /**
     * 扫描并处理未支付账单。独立成 public 方法便于单元测试直接调用。
     */
    public void reconcileNoPayBills() {
        List<PayBill> payBillList;
        try {
            payBillList = payBillMapper.selectList(Wrappers.lambdaQuery(PayBill.class)
                    .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode())
                    .eq(PayBill::getStatus, 1)
                    // 刚拉起支付的账单先不动：回调大概率还在路上
                    .le(PayBill::getPayTime, DateUtils.addMinute(DateUtils.now(), -NOT_BEFORE_MINUTES))
                    .ge(PayBill::getPayTime, DateUtils.addHour(DateUtils.now(), -LOOKBACK_HOURS))
                    .orderByAsc(PayBill::getPayTime)
                    .last("limit " + BATCH_SIZE));
        } catch (Exception e) {
            log.error("支付账单主动查单：查询未支付账单失败", e);
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_ERROR);
            return;
        }
        if (Objects.isNull(payBillList) || payBillList.isEmpty()) {
            return;
        }
        int paid = 0;
        int consecutiveQueryFailed = 0;
        for (PayBill payBill : payBillList) {
            try {
                String result = reconcileBill(payBill);
                if (Objects.equals(result, Metrics.RESULT_QUERY_FAILED)) {
                    consecutiveQueryFailed++;
                    if (consecutiveQueryFailed >= MAX_CONSECUTIVE_QUERY_FAILED) {
                        // 渠道像是整体不可用：本轮到此为止，别把剩下的账单再打一遍
                        log.warn("支付账单主动查单：连续 {} 笔查渠道失败，本轮提前结束", consecutiveQueryFailed);
                        BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                                Metrics.RESULT, Metrics.RESULT_QUERY_FAILED);
                        return;
                    }
                } else {
                    consecutiveQueryFailed = 0;
                }
                if (Objects.equals(result, Metrics.RESULT_PAID)) {
                    paid++;
                }
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                        Metrics.RESULT, result);
            } catch (Exception e) {
                // 单笔异常不影响后面的账单；不改状态，下轮重试
                log.error("支付账单主动查单：单笔处理异常 outOrderNo : {}", payBill.getOutOrderNo(), e);
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_BILL_RECONCILE_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_ERROR);
            }
        }
        if (paid > 0) {
            // 这是在报「回调丢了但钱到了」：金额正确、渠道确认已支付，我们自己却没收到通知
            log.warn("支付账单主动查单本轮发现 {} 笔「渠道已支付但本地账单仍是未支付」的账单，已补正。"
                    + "回调可能存在丢失，请检查通知地址与渠道回调日志", paid);
        }
    }

    /**
     * 单笔：问渠道真实状态，并按结果收敛本地账单。
     *
     * @return 结果分桶（用于指标 tag），取值见 {@link Metrics} 的 {@code RESULT_*} 常量
     */
    private String reconcileBill(PayBill payBill) {
        PayStrategyHandler payStrategyHandler;
        try {
            payStrategyHandler = payStrategyContext.get(payBill.getPayChannel());
        } catch (Exception e) {
            log.error("支付账单主动查单：渠道不存在 channel : {} outOrderNo : {}",
                    payBill.getPayChannel(), payBill.getOutOrderNo(), e);
            return Metrics.RESULT_QUERY_FAILED;
        }
        TradeResult tradeResult;
        try {
            tradeResult = payStrategyHandler.queryTrade(payBill.getOutOrderNo());
        } catch (Exception e) {
            log.error("支付账单主动查单：查渠道异常 outOrderNo : {}", payBill.getOutOrderNo(), e);
            return Metrics.RESULT_QUERY_FAILED;
        }
        if (Objects.isNull(tradeResult) || !tradeResult.isSuccess()) {
            // 查不到单/查询失败：既不能当已支付也不能当未支付，什么都不改
            log.warn("支付账单主动查单：渠道未返回有效交易 outOrderNo : {}", payBill.getOutOrderNo());
            return Metrics.RESULT_QUERY_FAILED;
        }
        Integer channelPayBillStatus = tradeResult.getPayBillStatus();
        if (Objects.equals(channelPayBillStatus, PayBillStatus.PAY.getCode())) {
            return markPaid(payBill, tradeResult.getTotalAmount());
        }
        if (Objects.equals(channelPayBillStatus, PayBillStatus.CANCEL.getCode())) {
            return markClosed(payBill);
        }
        // 渠道侧仍是待支付（WAIT_BUYER_PAY / NOTPAY）：正常状态，下一轮再看
        return Metrics.RESULT_UNPAID;
    }

    /**
     * 渠道确认已支付 → 条件更新（仅当本地仍是未支付）补成已支付。
     * <p>
     * 金额必须一致才动：账单金额是退款与对账的唯一依据，金额对不上说明渠道那笔交易不是我们这张单。
     */
    private String markPaid(PayBill payBill, BigDecimal channelAmount) {
        if (Objects.isNull(channelAmount) || Objects.isNull(payBill.getPayAmount())
                || channelAmount.compareTo(payBill.getPayAmount()) != 0) {
            log.error("支付账单主动查单：渠道已支付但金额不一致，不自动改状态，需人工核对 outOrderNo : {} "
                            + "渠道金额 : {} 账单金额 : {}",
                    payBill.getOutOrderNo(), channelAmount, payBill.getPayAmount());
            return Metrics.RESULT_AMOUNT_MISMATCH;
        }
        PayBill updatePayBill = new PayBill();
        updatePayBill.setPayBillStatus(PayBillStatus.PAY.getCode());
        updatePayBill.setPayTime(DateUtils.now());
        int updateCount = payBillMapper.update(updatePayBill, noPayCondition(payBill.getOutOrderNo()));
        if (updateCount <= 0) {
            // 0 行：回调或另一个实例已经先改了
            return Metrics.RESULT_CONCURRENT;
        }
        log.warn("支付账单主动查单：补正成功，渠道已支付但本地账单原本是未支付 outOrderNo : {}",
                payBill.getOutOrderNo());
        return Metrics.RESULT_PAID;
    }

    /**
     * 渠道侧交易已关闭（超时未支付被关单）→ 本地置已取消，让它离开候选集。
     * <p>
     * 只信渠道的「已关闭」：这个状态意味着该交易不可能再付款成功，置已取消不会挡掉任何笔真实入账。
     */
    private String markClosed(PayBill payBill) {
        PayBill updatePayBill = new PayBill();
        updatePayBill.setPayBillStatus(PayBillStatus.CANCEL.getCode());
        int updateCount = payBillMapper.update(updatePayBill, noPayCondition(payBill.getOutOrderNo()));
        if (updateCount <= 0) {
            return Metrics.RESULT_CONCURRENT;
        }
        log.info("支付账单主动查单：渠道交易已关闭，本地账单置已取消 outOrderNo : {}", payBill.getOutOrderNo());
        return Metrics.RESULT_CLOSED;
    }

    /**
     * 条件更新条件：本地仍是未支付才允许改（防止把回调刚写进来的已支付状态覆盖掉）。
     */
    private LambdaUpdateWrapper<PayBill> noPayCondition(String outOrderNo) {
        return Wrappers.lambdaUpdate(PayBill.class)
                .eq(PayBill::getOutOrderNo, outOrderNo)
                .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode());
    }
}
