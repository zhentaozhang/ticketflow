package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.constant.ProgramOrderConstant;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.service.OrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
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

import static com.ticketflow.core.DistributedLockConstants.UNPAID_ORDER_CANCEL_TASK_LOCK;

/**
 * 超时未支付订单定时兜底关单任务（双轨制保障）。
 * <p>
 * 背景：正常情况下订单由 Redisson 延迟队列在到期后实时触发取消关单。
 * 但若遇网络瞬断、延迟队列消息丢失或消费异常，未支付订单可能永远停留在 NO_PAY 状态，
 * 导致座位与余票库存形成僵尸占用。
 * <p>
 * 本任务作为兜底第二轨：
 * 定时（每 5 分钟）扫描创建时间超过超时窗口仍处于 NO_PAY 状态的订单，
 * 逐笔调用带分布式锁与 CAS 乐观锁保护的 {@link OrderService#cancel} 安全释放库存与座位。
 * <p>
 * 超时窗口由 {@link ProgramOrderConstant#DELAY_ORDER_CANCEL_TIME} 派生，
 * 确保兜底任务与延迟队列使用同一失效口径，不引入第二套超时定义。
 */
@Slf4j
@Component
public class UnpaidOrderCancelTask {

    /**
     * 判定为超时未支付的窗口（分钟）= 延迟队列的订单失效时长 + 兜底容差。
     * <p>
     * 从 {@link ProgramOrderConstant} 派生，与延迟队列共用同一口径：正常路径在
     * DELAY_ORDER_CANCEL_TIME 到期关单，本任务仅在其后 ORDER_CANCEL_FALLBACK_TOLERANCE_MINUTES
     * 介入，避免与延迟队列在同一时刻重复关单。
     */
    private static final int TIMEOUT_MINUTES = (int) (ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT
            .toMinutes(ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME)
            + ProgramOrderConstant.ORDER_CANCEL_FALLBACK_TOLERANCE_MINUTES);

    /**
     * 单轮最多补偿处理笔数，防止大促尖峰期单次扫表过载。
     */
    private static final int BATCH_SIZE = 50;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 专用单线程执行器（不排队，前一轮未完成则跳过本轮）
     */
    private final ThreadPoolExecutor cancelExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "unpaid-order-cancel-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> log.warn("上一轮超时未支付订单兜底关单还在执行，本轮跳过"));

    @PreDestroy
    public void shutdown() {
        cancelExecutor.shutdown();
    }

    /**
     * 每 5 分钟执行一轮
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void runCancelTask() {
        cancelExecutor.execute(() -> {
            RLock lock = serviceLockTool.getLock(LockType.Reentrant, UNPAID_ORDER_CANCEL_TASK_LOCK, new String[]{"all"});
            boolean locked = false;
            try {
                locked = lock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("其他实例正在执行超时未支付订单兜底关单，本轮跳过");
                    return;
                }
                processTimeoutOrders();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("超时未支付订单关单任务获取分布式锁被中断", e);
            } catch (Exception e) {
                log.error("超时未支付订单关单任务执行异常", e);
            } finally {
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception e) {
                        log.warn("释放超时未支付订单关单任务互斥锁失败", e);
                    }
                }
            }
        });
    }

    public void processTimeoutOrders() {
        List<Order> timeoutOrders = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderStatus, OrderStatus.NO_PAY.getCode())
                .ge(Order::getCreateOrderTime, DateUtils.addHour(DateUtils.now(), -24))
                .le(Order::getCreateOrderTime, DateUtils.addMinute(DateUtils.now(), -TIMEOUT_MINUTES))
                .orderByAsc(Order::getCreateOrderTime)
                .last("limit " + BATCH_SIZE));

        if (Objects.isNull(timeoutOrders) || timeoutOrders.isEmpty()) {
            return;
        }

        log.info("超时未支付订单定时关单任务扫出 {} 笔僵尸订单，准备执行兜底关单", timeoutOrders.size());
        int successCount = 0;
        for (Order order : timeoutOrders) {
            try {
                OrderCancelDto cancelDto = new OrderCancelDto();
                cancelDto.setOrderNumber(order.getOrderNumber());
                boolean cancelled = orderService.cancel(cancelDto);
                if (cancelled) {
                    successCount++;
                }
            } catch (Exception e) {
                // 单笔失败不阻断后续订单，下轮对账重试
                log.error("超时未支付订单兜底关单失败 orderNumber : {}", order.getOrderNumber(), e);
            }
        }
        log.info("超时未支付订单定时关单完成，成功关单数 : {}/{}", successCount, timeoutOrders.size());
    }
}
