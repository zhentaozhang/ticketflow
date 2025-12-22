package com.ticketflow.service.strategy.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V2;

/**
 * 节目订单 V2 实现（手动锁版本）。
 * 与 V1 区别：按 ticketCategoryId 细粒度拆分锁，每个票档一把 Redis 锁。
 * 先批量尝试获取所有锁 → 全部成功后调用 create() → finally 反向依次释放。
 *
 * 同时使用两层锁：
 *   - LocalLockCache（Caffeine + ReentrantLock）作为本地屏障
 *   - ServiceLockTool（Redis/Redisson）作为分布式屏障
 *
 * V21 与 V2 的关系：代码结构与 V2 相同但使用不同的锁策略（V2 用 @ServiceLock 注解，
 * V21 用手动 lock()/unlock() 提供更灵活的锁管理）
 */
@Slf4j
@Component
public class ProgramOrderV21Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @Autowired
    private LocalLockCache localLockCache;
    
    
    /**
     * 创建订单（V21 手动锁版本）
     * 手动管理分布式锁生命周期，锁逻辑独立于 BaseProgramOrder
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号
     */
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        // V21 与 V2 结构相同，差异：V21 只维护分布式锁列表，本地锁由上游 BaseProgramOrder 统一管控
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            ticketCategoryIdList =
                    seatDtoList.stream().map(SeatDto::getTicketCategoryId).distinct().sorted().collect(Collectors.toList());
        }else {
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }
        List<RLock> serviceLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<RLock> serviceLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        for (Long ticketCategoryId : ticketCategoryIdList) {
            String lockKey = StrUtil.join("-",PROGRAM_ORDER_CREATE_V2,
                    programOrderCreateDto.getProgramId(),ticketCategoryId);
            ReentrantLock localLock = localLockCache.getLock(lockKey,false);
            RLock serviceLock = serviceLockTool.getLock(LockType.Reentrant, lockKey);
            serviceLockList.add(serviceLock);
        }
        // 逐个获取分布式锁，任一失败 break 后已获取的锁在 finally 中逆序释放
        for (RLock rLock : serviceLockList) {
            try {
                rLock.lock();
            }catch (Exception e) {
                break;
            }
            serviceLockSuccessList.add(rLock);
        }
        try {
            return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V21_VERSION.getValue());
        }finally {
            // 逆序释放分布式锁（后加先释放），防止死锁
            for (int i = serviceLockSuccessList.size() - 1; i >= 0; i--) {
                RLock rLock = serviceLockSuccessList.get(i);
                try {
                    rLock.unlock();
                }catch (Exception e) {
                    log.error("service lock unlock error",e);
                }
            }
        }
    }
    
    /**
     * 获取版本号
     *
     * @return V21_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V21_VERSION.getVersion();
    }
}
