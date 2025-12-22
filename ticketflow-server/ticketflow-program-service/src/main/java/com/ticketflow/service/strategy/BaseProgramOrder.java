package com.ticketflow.service.strategy;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.lock.LockTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 节目下单基础逻辑（双重锁模板）。
 * 本地锁（ReentrantLock per ticketCategoryId）→ 回调执行分布式锁逻辑，
 * 形成反向释放顺序防止死锁，被 V2/V3/V4 策略实现类调用
 */
@Slf4j
@Component
public class BaseProgramOrder {
    
    @Autowired
    private LocalLockCache localLockCache;
    
    /**
     * 双層锁：本地锁（按 ticketCategoryId 加 ReentrantLock）→ 分布式锁（Redisson，在策略实现类中）
     * 本地锁先于分布式锁获取，后于分布式锁释放，形成相反的释放顺序，
     * 避免分布式锁释放后其他线程立即重入，本地锁还未释放导致的并发问题。
     *
     * @param lockKeyPrefix          锁 key 前缀（区分不同策略版本）
     * @param programOrderCreateDto  订单创建请求参数（含座位/票档信息）
     * @param lockTask               分布式锁回调（策略实现类中的实际下单逻辑）
     * @return 订单编号
     */
    public String localLockCreateOrder(String lockKeyPrefix, ProgramOrderCreateDto programOrderCreateDto, 
                                            LockTask<String> lockTask){
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
        // 第三步：逐个加锁，任一本地锁失败则立即 break（剩余锁不获取，交由上层策略类处理）
        for (ReentrantLock reentrantLock : localLockList) {
            try {
                reentrantLock.lock();
            }catch (Exception e) {
                break;
            }
            localLockSuccessList.add(reentrantLock);
        }
        try {
            // 第四步：执行回调——分布式锁逻辑由调用方在 lockTask 中实现（独立于本地锁管控）
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
