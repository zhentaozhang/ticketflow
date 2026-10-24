package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.RefundStatus;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.mapper.RefundBillMapper;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.REFUND_CHECK_TASK_LOCK;

/**
 * 退款状态确认定时任务（默认每5分钟）。
 *
 * <p>轮询两类退款单：
 * <ul>
 *   <li>1 处理中（微信退款可能异步完成）：查渠道，成功则落库并累计判断账单是否可置 REFUND；</li>
 *   <li>0 受理待确认：退款调用发出去了但没拿到结果（超时/进程崩溃）。这类单的钱可能已经退给用户，
 *       既不能当成功也不能当失败，只能<b>主动查渠道</b>确认；</li>
 * </ul>
 * 渠道明确失败的置终态（3）并打指标告警，避免对同一退款单无限重试。
 */
@Slf4j
@Component
public class RefundCheckTask {

    /**
     * 单轮最多确认多少笔：退款单会持续累积 0 态单，不限量会让一轮变成全表扫描
     */
    private static final int CHECK_BATCH_SIZE = 200;

    @Autowired
    private RefundBillMapper refundBillMapper;

    @Autowired
    private PayBillMapper payBillMapper;

    @Autowired
    private PayStrategyContext payStrategyContext;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 退款确认指标（registry 缺失时静默降级）
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 0 态退款单超过这个时长仍未确认，说明渠道一直没给出结果，必须叫人
     */
    private static final long ACCEPT_ALERT_MINUTES = 24 * 60L;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void refundCheckTask() {
        BusinessThreadPool.execute(() -> {
            RLock lock = serviceLockTool.getLock(LockType.Reentrant, REFUND_CHECK_TASK_LOCK, new String[]{"all"});
            boolean locked = false;
            try {
                locked = lock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("其他实例正在执行退款状态确认任务，本轮跳过");
                    return;
                }
                processPendingRefunds();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("退款状态确认任务获取互斥锁被中断", e);
            } catch (Exception e) {
                log.error("退款状态确认任务执行异常", e);
            } finally {
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception e) {
                        log.warn("释放退款状态确认任务互斥锁失败", e);
                    }
                }
            }
        });
    }

    /**
     * 处理所有处理中的退款单。独立成 public 方法便于单元测试直接调用。
     * 注意：与 PayService.refund 并发执行时不持有 COMMON_PAY 锁，
     * 翻转账单状态使用条件更新（eq PAY）防覆盖；超退由渠道侧
     * "退款金额不得超过原订单金额"校验兜底。
     */
    public void processPendingRefunds() {
        try {
            // 0 态（受理待确认）与 1 态（处理中）都要查渠道：0 态的钱可能已经退出去了
            List<RefundBill> refundBillList = refundBillMapper.selectList(
                    Wrappers.lambdaQuery(RefundBill.class)
                            .in(RefundBill::getRefundStatus,
                                    RefundStatus.REFUND_ACCEPT.getCode(),
                                    RefundStatus.REFUND_PROCESSING.getCode())
                            .eq(RefundBill::getStatus, 1)
                            .orderByAsc(RefundBill::getCreateTime)
                            .last("limit " + CHECK_BATCH_SIZE));
            if (refundBillList.isEmpty()) {
                return;
            }
            log.info("退款状态确认任务执行，待确认退款单数量 : {}", refundBillList.size());
            for (RefundBill refundBill : refundBillList) {
                try {
                    checkRefund(refundBill);
                } catch (Exception e) {
                    log.error("退款状态确认异常 refundBillId : {}", refundBill.getId(), e);
                    BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_CHECK_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_ERROR);
                }
            }
        } catch (Exception e) {
            log.error("refund check task error", e);
        }
    }

    private void checkRefund(RefundBill refundBill) {
        PayBill payBill = payBillMapper.selectById(refundBill.getPayBillId());
        if (Objects.isNull(payBill)) {
            log.error("退款确认：支付账单不存在 refundBillId : {}", refundBill.getId());
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_CHECK_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_ERROR);
            return;
        }
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(payBill.getPayChannel());
        RefundResult refundResult =
                payStrategyHandler.queryRefund(payBill.getOutOrderNo(), refundBill.getOutRefundNo());
        if (Objects.equals(refundResult.getRefundStatus(), RefundStatus.REFUND_SUCCESS.getCode())) {
            // 退款成功：更新退款单状态，累计已退达到支付金额则账单置为已退款（条件更新防并发覆盖）
            RefundBill updateRefundBill = new RefundBill();
            updateRefundBill.setId(refundBill.getId());
            updateRefundBill.setRefundStatus(RefundStatus.REFUND_SUCCESS.getCode());
            updateRefundBill.setRefundTime(DateUtils.now());
            refundBillMapper.updateById(updateRefundBill);
            // 已确认到账累计（只统计 refund_status=2）：在途单最终可能失败，
            // 按含在途累计判断会导致在途单失败后账单已置 REFUND 无法继续退款，造成少退
            BigDecimal confirmedRefundedAmount = refundBillMapper.selectList(
                            Wrappers.lambdaQuery(RefundBill.class)
                                    .eq(RefundBill::getOutOrderNo, payBill.getOutOrderNo())
                                    .eq(RefundBill::getStatus, 1)
                                    .eq(RefundBill::getRefundStatus, RefundStatus.REFUND_SUCCESS.getCode()))
                    .stream()
                    // 再按状态过滤一次：与 PayService 的累计口径保持一致
                    .filter(bill -> Objects.equals(bill.getRefundStatus(), RefundStatus.REFUND_SUCCESS.getCode()))
                    .map(RefundBill::getRefundAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (confirmedRefundedAmount.compareTo(payBill.getPayAmount()) >= 0) {
                PayBill updatePayBill = new PayBill();
                updatePayBill.setPayBillStatus(PayBillStatus.REFUND.getCode());
                payBillMapper.update(updatePayBill, Wrappers.lambdaUpdate(PayBill.class)
                        .eq(PayBill::getOutOrderNo, payBill.getOutOrderNo())
                        .eq(PayBill::getPayBillStatus, PayBillStatus.PAY.getCode()));
            }
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_CHECK_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_SUCCESS);
            return;
        }
        if (Objects.equals(refundResult.getRefundStatus(), RefundStatus.REFUND_PROCESSING.getCode())) {
            // 仍在处理中，留待下轮轮询
            log.info("退款仍在处理中 refundBillId : {} outRefundNo : {}",
                    refundBill.getId(), refundBill.getOutRefundNo());
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_CHECK_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_PROCESSING);
            return;
        }
        // 渠道明确失败/关闭/查不到：置终态并告警，避免无限重试。
        // 0 态单走到这里说明渠道侧没有这笔退款（钱没有退出去），置 3 后订单侧可用同一幂等键重入；
        // 若渠道其实已退款却查不到，属于渠道侧异常，指标与日志是唯一的人工入口
        boolean accepted = Objects.equals(refundBill.getRefundStatus(), RefundStatus.REFUND_ACCEPT.getCode());
        long pendingMinutes = Objects.isNull(refundBill.getCreateTime())
                ? 0L : (DateUtils.now().getTime() - refundBill.getCreateTime().getTime()) / 60000L;
        if (accepted && pendingMinutes >= ACCEPT_ALERT_MINUTES) {
            log.error("退款受理后 {} 分钟仍未确认，转人工核对 refundBillId : {} outRefundNo : {} message : {}",
                    pendingMinutes, refundBill.getId(), refundBill.getOutRefundNo(), refundResult.getMessage());
        }
        log.error("退款确认失败 refundBillId : {} outRefundNo : {} message : {}",
                refundBill.getId(), refundBill.getOutRefundNo(), refundResult.getMessage());
        RefundBill updateRefundBill = new RefundBill();
        updateRefundBill.setId(refundBill.getId());
        updateRefundBill.setRefundStatus(RefundStatus.REFUND_FAIL.getCode());
        refundBillMapper.updateById(updateRefundBill);
        BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_CHECK_TOTAL,
                Metrics.RESULT, Metrics.RESULT_FAIL);
    }
}
