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
 * 按 ticketCategoryId 加本地锁（ReentrantLock per ticketCategoryId），
 * 回调中是否叠加分布式锁由策略实现决定；并发安全的最终底线是带校验的 Lua。
 * 被 V2/V3/V31/V4/V41 策略实现类调用
 */
@Slf4j
@Component
public class BaseProgramOrder {

    private static final long LOCK_WAIT_TIME = 3L;

    @Autowired
    private LocalLockCache localLockCache;
    
    /**
     * 本地锁（按 ticketCategoryId 加 ReentrantLock），回调中由策略决定是否叠加分布式锁。
     * 本地锁先于分布式锁获取，后于分布式锁释放，形成相反的释放顺序，
     * 避免分布式锁释放后其他线程立即重入，本地锁还未释放导致的并发问题。
     *
     * @param lockKeyPrefix          锁 key 前缀（区分不同策略版本）
     * @param programOrderCreateDto  订单创建请求参数（含座位/票档信息）
     * @param lockTask               下单回调（策略实现类中的实际下单逻辑）
     * @return 订单编号
     */
    public String localLockCreateOrder(String lockKeyPrefix, ProgramOrderCreateDto programOrderCreateDto,
                                            LockTask<String> lockTask){
        return localLockExecute(lockKeyPrefix, programOrderCreateDto, lockTask);
    }

    /**
     * 本地锁（按 ticketCategoryId 加 ReentrantLock）泛型模板，回调结果类型由调用方指定。
     * 锁内只应保留必须互斥的临界操作（如 Lua 扣减），耗时操作（如 Kafka 发送）应放在锁外，
     * 以缩短锁持有时间、降低锁竞争失败率。
     *
     * @param lockKeyPrefix          锁 key 前缀（区分不同策略版本）
     * @param programOrderCreateDto  订单创建请求参数（含座位/票档信息）
     * @param lockTask               锁内回调，返回任意类型结果
     * @param <T>                    回调返回类型
     * @return 锁内回调的执行结果
     */
    public <T> T localLockExecute(String lockKeyPrefix, ProgramOrderCreateDto programOrderCreateDto,
                                      LockTask<T> lockTask){
        // 第一步：提取不重复的票价档位 ID（选座时从 seatDtoList 提取，不选座时直接取 ticketCategoryId）
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            ticketCategoryIdList =
                    seatDtoList.stream().map(SeatDto::getTicketCategoryId).distinct().sorted().collect(Collectors.toList());
        }else {
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }
        // 第二步：为每个档位构建本地锁 key，存入列表（此时尚未加锁）
        List<ReentrantLock> localLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<ReentrantLock> localLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        for (Long ticketCategoryId : ticketCategoryIdList) {
            String lockKey = StrUtil.join("-",lockKeyPrefix,
                    programOrderCreateDto.getProgramId(),ticketCategoryId);
            ReentrantLock localLock = localLockCache.getLock(lockKey,false);
            localLockList.add(localLock);
        }
        // 第三步：逐个加锁（限时等待），任一本地锁超时/中断则停止获取并快速失败，不执行下单
        boolean localLockFail = false;
        for (ReentrantLock reentrantLock : localLockList) {
            try {
                if (reentrantLock.tryLock(LOCK_WAIT_TIME, TimeUnit.SECONDS)) {
                    localLockSuccessList.add(reentrantLock);
                } else {
                    localLockFail = true;
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                localLockFail = true;
                break;
            }
        }
        try {
            if (localLockFail) {
                throw new TicketFlowFrameException(BaseCode.SERVICE_LOCK_FAIL);
            }
            // 第四步：执行回调——是否叠加分布式锁由策略实现类在 lockTask 中决定
            return lockTask.execute();
        }finally {
            // 第五步：反向释放（后加的先释放），防止锁依赖顺序不一致导致死锁
            for (int i = localLockSuccessList.size() - 1; i >= 0; i--) {
                ReentrantLock reentrantLock = localLockSuccessList.get(i);
                try {
                    reentrantLock.unlock();
                }catch (Exception e) {
                    log.error("local lock unlock error",e);
                }
            }
        }
    }
}
