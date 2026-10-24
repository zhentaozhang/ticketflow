package com.ticketflow.scheduletask;

import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.service.OrderProgramSyncService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.ORDER_PROGRAM_SYNC_TASK_LOCK;

/**
 * 节目数据同步任务：把订单 outbox（{@code d_order.program_sync_*}）里没推成功的节目数据推到目标状态。
 * <p>
 * <b>为什么需要一个任务</b>：支付/取消时 Redis 由 Lua 原子收敛，但节目服务的 DB 是派生态，
 * 靠事务外一次 Feign 调用推进。那次调用会失败（超时、节目服务重启、网络抖动），
 * 而失败只会留下一行日志——Redis 与 DB 从此永久分叉，座位在 Redis 里已售出、在 DB 里还是锁定，
 * 后台报表、选座校验全部读到脏数据，且没有任何东西会再去修正它。
 * 现在这次"必须完成"的推送被登记进订单行（和状态 CAS 同一个本地事务），失败由这里重试到成功或转死信。
 * <p>
 * <b>两条不可违反的规则</b>：
 * <ul>
 *   <li><b>不重放 Lua</b>：Lua 的取消分支是无条件归还余量，重放会双倍还票。这里只重推 Feign，绝不重放脚本。</li>
 *   <li><b>Redis 不收敛就不推 DB</b>：Redis 是权威源。这道判断在 {@link OrderProgramSyncService} 内完成，
 *       未收敛的记录只会退避，不会让 DB 跑到 Redis 前面。</li>
 * </ul>
 * <p>
 * 与 {@link PaymentReconciliationTask} 同一套执行模型：专用单线程 + 不排队 + 多实例互斥 + 被跳过记指标。
 */
@Slf4j
@Component
public class OrderProgramSyncTask {

    /**
     * 同步轮次的互斥 key（多实例只让一个跑）。
     */
    private static final String[] SYNC_LOCK_KEYS = {"all"};

    /**
     * 差异扫描的互斥 key：和同步轮次用不同的 key，避免互相把对方锁掉。
     */
    private static final String[] SCAN_LOCK_KEYS = {"unsynced_scan"};

    @Autowired
    private OrderProgramSyncService orderProgramSyncService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 同步轮次的执行器：单线程 + 不排队。上一轮没跑完就跳过本轮（记 skipped），
     * 不用业务线程池，避免抢票尖峰期任务被饿死。
     */
    private final ThreadPoolExecutor programSyncExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "order-program-sync-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> {
                log.warn("上一轮节目数据同步还在执行，本轮跳过");
                BusinessMetrics.increment(meterRegistry, Metrics.ORDER_PROGRAM_SYNC_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_SKIPPED);
            });

    /**
     * 差异扫描的执行器：与同步轮次分开，避免"扫描把同步挤掉"。
     */
    private final ThreadPoolExecutor unsyncedScanExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "order-program-unsynced-scan-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> log.warn("上一轮节目数据同步差异扫描还在执行，本轮跳过"));

    @PreDestroy
    public void shutdown() {
        programSyncExecutor.shutdown();
        unsyncedScanExecutor.shutdown();
    }

    /**
     * 每分钟推进一批到期的待同步记录。
     * <p>
     * 频次选 1 分钟而不是更短：首次推送已经在 afterCommit 里做过一次，
     * 这里处理的是"那一次没成功"的少量残留，1 分钟的量级对用户体验（后台报表/选座校验）足够。
     */
    @Scheduled(cron = "0 0/1 * * * ? ")
    public void syncProgramData() {
        programSyncExecutor.execute(() -> {
            RLock roundLock = serviceLockTool.getLock(LockType.Reentrant, ORDER_PROGRAM_SYNC_TASK_LOCK, SYNC_LOCK_KEYS);
            boolean locked = false;
            try {
                // 不等待：抢不到说明另一实例正在跑，直接跳过，下一分钟还会再来
                locked = roundLock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    BusinessMetrics.increment(meterRegistry, Metrics.ORDER_PROGRAM_SYNC_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_SKIPPED);
                    return;
                }
                int settled = orderProgramSyncService.syncDueOrders();
                if (settled > 0) {
                    log.info("节目数据同步本轮收敛 {} 笔", settled);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("节目数据同步获取锁被中断", e);
            } catch (Exception e) {
                // 任务本身异常必须吞掉，否则调度线程会被取消后续触发
                log.error("节目数据同步任务执行失败", e);
            } finally {
                if (locked) {
                    roundLock.unlock();
                }
            }
        });
    }

    /**
     * 每 5 分钟做一次差异扫描：找"已经终态却没有登记同步"的 V4 订单。
     * <p>
     * 正常情况下这样的行不存在（入队和状态 CAS 同事务）。真出现就说明有写路径绕过了 outbox，
     * 而这些行没有 payload、无法自动重放——只上报指标和日志，交人工按订单核对节目服务 DB。
     * 扫描是只读的，但也要互斥：否则多实例会重复上报同一批订单，让告警失去意义。
     */
    @Scheduled(cron = "0 0/5 * * * ? ")
    public void scanUnsyncedTerminalOrders() {
        unsyncedScanExecutor.execute(() -> {
            RLock scanLock = serviceLockTool.getLock(LockType.Reentrant, ORDER_PROGRAM_SYNC_TASK_LOCK, SCAN_LOCK_KEYS);
            boolean locked = false;
            try {
                locked = scanLock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    return;
                }
                orderProgramSyncService.scanTerminalWithoutSync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("节目数据同步差异扫描获取锁被中断", e);
            } catch (Exception e) {
                log.error("节目数据同步差异扫描执行失败", e);
            } finally {
                if (locked) {
                    scanLock.unlock();
                }
            }
        });
    }
}
