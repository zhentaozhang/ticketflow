package com.ticketflow.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramRecordTaskListDto;
import com.ticketflow.dto.ProgramRecordTaskUpdateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.HandleStatus;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RLock;
import com.ticketflow.service.OrderTaskService;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramRecordTaskVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.RECONCILIATION_TASK_LOCK;

/**
 * Redis ↔ DB 数据对账定时任务（默认3分钟间隔，已注释）。
 * 扫描 ProgramRecordTask 中未处理的记录，
 * 检测 Redis 中 lock 超时的座位，清理并恢复余票，
 * 确保极端情况下座位数据最终一致
 */
@Slf4j
@Component
public class ReconciliationTask {

    @Autowired
    private OrderTaskService orderTaskService;

    @Autowired
    private ProgramClient programClient;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ServiceLockTool serviceLockTool;

    /**
     * 对账专用执行器：单线程 + 不排队（SynchronousQueue）。
     * <p>
     * 为什么不用 {@code BusinessThreadPool}：
     * <ol>
     *   <li>那是业务异步任务共用的池子，尖峰期正它最满——而对账恰恰是尖峰之后最该跑的东西；
     *       池子满了会抛 RejectedExecutionException，那一轮对账就没了（只有一条 warn，没有指标、没有告警）。</li>
     *   <li>“单线程 + 不排队”的语义是“上一轮还没跑完，这一轮就跳过”。
     *       对账扫的是“3 分钟前仍未处理”的记录，跳过一轮不会漏任何数据（下一轮扫同一个窗口），
     *       但两轮并发跑只会让数据库多做一遍同样的活。</li>
     * </ol>
     * 被跳过时记 {@code result=skipped} 指标，不再静默。
     */
    private final ThreadPoolExecutor reconciliationExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "reconciliation-task");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> {
                // 上一轮还在跑：记指标 + 日志，然后跳过（不抛异常，避免把调度线程也干扰到）
                log.warn("上一轮对账还在执行，本轮跳过");
                BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_TASK_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_SKIPPED);
            });

    @PreDestroy
    public void shutdown() {
        reconciliationExecutor.shutdown();
    }

    //对账任务每1分钟执行一次：补偿 DB 有单 Redis 无流水的记录，并回滚 DISCARD_ORDER 丢弃订单的 Redis 扣减
    @Scheduled(cron = "0 0/1 * * * ? ")
    public void reconciliationTask() {
        reconciliationExecutor.execute(() -> {
            // 多实例互斥：对账扫的是同一批数据，补偿又是幂等的，所以多实例同时跑不会算错，
            // 但会把同一份库压力乘上实例数。同一个业务名 + 不等待（waitTime=0）的锁，
            // 语义就是“这轮已经有实例在跑了，我跳过”。
            RLock roundLock = serviceLockTool.getLock(LockType.Reentrant, RECONCILIATION_TASK_LOCK,
                    new String[] {"all"});
            boolean locked = false;
            try {
                locked = roundLock.tryLock(0, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("其它实例正在执行对账，本轮跳过");
                    BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_TASK_TOTAL,
                            Metrics.RESULT, Metrics.RESULT_SKIPPED);
                    return;
                }
                reconcile();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("对账任务获取互斥锁被中断", e);
            } finally {
                if (locked) {
                    try {
                        roundLock.unlock();
                    } catch (Exception e) {
                        // 释放失败不影响本轮结果：锁到期会自动释放
                        log.warn("释放对账互斥锁失败", e);
                    }
                }
            }
        });
    }

    private void reconcile() {
        try {
            log.info("对账任务执行");
            ProgramRecordTaskListDto programRecordTaskListDto = new ProgramRecordTaskListDto();
            programRecordTaskListDto.setHandleStatus(HandleStatus.NO_HANDLE.getCode());
            //查询当前时间前3分钟的对账记录
            programRecordTaskListDto.setCreateTime(DateUtils.addMinute(DateUtils.now(), -3));
            ApiResponse<List<ProgramRecordTaskVo>> listApiResponse = programClient.select(programRecordTaskListDto);
            if (!Objects.equals(listApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                log.error("获取节目对账记录任务集合失败 dto : {} message: {}", JSON.toJSONString(programRecordTaskListDto), listApiResponse.getMessage());
                return;
            }
            List<ProgramRecordTaskVo> programRecordTaskVoList = listApiResponse.getData();
            if (CollectionUtil.isEmpty(programRecordTaskVoList)) {
                log.warn("获取节目对账记录任务集合为空 dto : {}", JSON.toJSONString(programRecordTaskListDto));
                BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_TASK_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_EMPTY);
                return;
            }
            Set<Long> programIdSet = new HashSet<>();
            Set<Date> createTimeSet = new HashSet<>();
            for (ProgramRecordTaskVo programRecordTaskVo : programRecordTaskVoList) {
                programIdSet.add(programRecordTaskVo.getProgramId());
                createTimeSet.add(programRecordTaskVo.getCreateTime());
            }
            // 补偿触发源不依赖对账记录：若对账记录写入失败（线程池满且同步也失败）或某节目从未成功
            // 写过记录，DISCARD_ORDER / PENDING 列表即使非空也不会被扫描 → 库存黑洞。
            // 这里用 SCAN 把这两类列表涉及的节目 id 并入触发集合兜底。
            programIdSet.addAll(scanProgramIdsByKeyPattern("*" + RedisKeyManage.DISCARD_ORDER.getKey().replace("%s", "*")));
            programIdSet.addAll(scanProgramIdsByKeyPattern("*" + RedisKeyManage.ORDER_CREATE_PENDING.getKey().replace("%s", "*")));
            for (Long programId : programIdSet) {
                BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_COMPENSATE_TOTAL);
                orderTaskService.reconciliationTask(programId);
                orderTaskService.discardOrderCompensation(programId);
                //PENDING 发送超时订单补偿：已建单则移除，未建单则回滚 Redis 座位
                orderTaskService.pendingOrderCompensation(programId);
            }
            //修改对账记录任务集合为已处理
            ProgramRecordTaskUpdateDto programRecordTaskUpdateDto = new ProgramRecordTaskUpdateDto();
            programRecordTaskUpdateDto.setBeforeHandleStatus(HandleStatus.NO_HANDLE.getCode());
            programRecordTaskUpdateDto.setAfterHandleStatus(HandleStatus.YES_HANDLE.getCode());
            programRecordTaskUpdateDto.setCreateTimeSet(createTimeSet);
            ApiResponse<Integer> updateApiResponse = programClient.update(programRecordTaskUpdateDto);
            if (!Objects.equals(updateApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                log.error("更新节目对账记录任务失败 dto : {} message: {}", JSON.toJSONString(programRecordTaskUpdateDto), updateApiResponse.getMessage());
            }
            BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_TASK_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_SUCCESS);
        } catch (Exception e) {
            BusinessMetrics.increment(meterRegistry, Metrics.RECONCILIATION_TASK_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_ERROR);
            log.error("reconciliation task error", e);
        }
    }

    /**
     * 按 key 通配模式 SCAN 出节目 id（用 SCAN 而非 KEYS，避免阻塞 Redis）。
     * 从 "{前缀}-d_mai_discard_order_{programId}" / "{前缀}-d_mai_order_create_pending_{programId}"
     * 这类 key 中提取末尾的 programId，仅作为补偿触发源的兜底，失败不影响主流程。
     *
     * @param pattern key 通配模式（如 "*d_mai_discard_order_*"）
     * @return 命中的节目 id 集合（可能为空）
     */
    private Set<Long> scanProgramIdsByKeyPattern(String pattern) {
        Set<Long> programIdSet = new HashSet<>();
        try {
            List<String> keys = (List<String>) redisCache.getInstance().execute((RedisCallback<List<String>>) connection -> {
                List<String> matchedKeys = new ArrayList<>();
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                        ScanOptions.scanOptions().match(pattern).count(1000).build())) {
                    while (cursor.hasNext()) {
                        matchedKeys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return matchedKeys;
            });
            if (CollectionUtil.isEmpty(keys)) {
                return programIdSet;
            }
            for (String key : keys) {
                int idx = key.lastIndexOf('_');
                if (idx > 0 && idx < key.length() - 1) {
                    try {
                        programIdSet.add(Long.valueOf(key.substring(idx + 1)));
                    } catch (NumberFormatException ignored) {
                        // 非数字后缀的 key 忽略（占位/测试 key）
                    }
                }
            }
        } catch (Exception e) {
            log.error("扫描补偿触发 key 失败 pattern : {}", pattern, e);
        }
        return programIdSet;
    }
}
