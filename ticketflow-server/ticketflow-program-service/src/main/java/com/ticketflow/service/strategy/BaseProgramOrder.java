package com.ticketflow.service.strategy;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.lock.LockTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 节目下单基础逻辑（本地锁模板）。
 * 按 programId + ticketCategoryId 进行细粒度加锁，
 * 具体策略是否叠加分布式锁由 LockTask 决定。
 */
@Slf4j
@Component
public class BaseProgramOrder {

    // 本地锁最多等待 3 秒，避免线程长时间阻塞。
    private static final long LOCK_WAIT_TIME = 3L;

    @Autowired
    private LocalLockCache localLockCache;

    /**
     * 本地锁下单入口。
     */
    public String localLockCreateOrder(String lockKeyPrefix, ProgramOrderCreateDto programOrderCreateDto,
                                       LockTask<String> lockTask) {
        return localLockExecute(lockKeyPrefix, programOrderCreateDto, lockTask);
    }

    /**
     * 通用本地锁模板：
     * 先获取所有需要的本地锁，再执行下单逻辑，最后统一释放。
     */
    public <T> T localLockExecute(String lockKeyPrefix, ProgramOrderCreateDto programOrderCreateDto,
                                  LockTask<T> lockTask) {

        // 第一步：获取本次请求涉及的所有票档 ID。
        // 选座场景从座位列表中提取并去重；不选座场景直接使用 ticketCategoryId。
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();

        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // 按 ticketCategoryId 去重并排序，保证多个锁始终按照固定顺序获取，避免死锁。
            ticketCategoryIdList =
                    seatDtoList.stream()
                            .map(SeatDto::getTicketCategoryId)
                            .distinct()
                            .sorted()
                            .collect(Collectors.toList());
        } else {
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }

        // 第二步：根据 programId + ticketCategoryId 获取对应的本地锁。
        // 锁粒度细化到票档，避免整个节目共用一把大锁。
        List<ReentrantLock> localLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<ReentrantLock> localLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());

        for (Long ticketCategoryId : ticketCategoryIdList) {
            String lockKey = StrUtil.join("-",
                    lockKeyPrefix,
                    programOrderCreateDto.getProgramId(),
                    ticketCategoryId);

            ReentrantLock localLock = localLockCache.getLock(lockKey, false);
            localLockList.add(localLock);
        }

        // 第三步：按固定顺序依次加锁，最多等待 3 秒。
        boolean localLockFail = false;

        for (ReentrantLock reentrantLock : localLockList) {
            try {
                if (reentrantLock.tryLock(LOCK_WAIT_TIME, TimeUnit.SECONDS)) {
                    // 记录实际获取成功的锁，后续只释放这些锁。
                    localLockSuccessList.add(reentrantLock);
                } else {
                    localLockFail = true;
                    break;
                }
            } catch (InterruptedException e) {
                // 被中断时恢复中断标记，并按获取锁失败处理。
                Thread.currentThread().interrupt();
                localLockFail = true;
                break;
            }
        }

        try {
            // 有任意一把锁获取失败，就不执行下单逻辑。
            if (localLockFail) {
                throw new TicketFlowFrameException(BaseCode.SERVICE_LOCK_FAIL);
            }

            // 第四步：执行具体下单逻辑。
            // 是否叠加 Redis 分布式锁，由具体策略在 LockTask 中决定。
            return lockTask.execute();

        } finally {
            // 第五步：按后加先释放的顺序释放锁，保证资源正确回收。
            for (int i = localLockSuccessList.size() - 1; i >= 0; i--) {
                ReentrantLock reentrantLock = localLockSuccessList.get(i);
                try {
                    reentrantLock.unlock();
                } catch (Exception e) {
                    log.error("local lock unlock error", e);
                }
            }
        }
    }
}