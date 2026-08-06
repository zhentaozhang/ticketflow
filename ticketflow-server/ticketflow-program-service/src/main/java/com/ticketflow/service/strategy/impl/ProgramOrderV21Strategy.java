package com.ticketflow.service.strategy.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.exception.TicketFlowFrameException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V2;

/**
 * 节目订单 V21 实现（手动双层锁版本，V2 的独立注册变体）。
 * 与 V2 采用相同的双层锁结构：
 *   - LocalLockCache（Caffeine + ReentrantLock）作为本地屏障
 *   - ServiceLockTool（Redis/Redisson）作为分布式屏障
 * 全部获取成功后才调用 create()，finally 逆序释放；锁获取带 3 秒超时，超时快速失败。
 * 与 V2 的差异仅在于版本标识（v21）与调用入口，实现结构一致。
 */
@Slf4j
@Component
public class ProgramOrderV21Strategy implements ProgramOrderStrategy {

    private static final long LOCK_WAIT_TIME = 3L;
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @Autowired
    private LocalLockCache localLockCache;
    
    
    /**
     * 创建订单（V21 手动双层锁版本）
     * 按 ticketCategoryId 逐档位加本地锁 + 分布式锁，全部成功后同步 DB 写入
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
        // V21 与 V2 结构相同：按票档拆分的本地锁 + 分布式锁双层屏障
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            ticketCategoryIdList =
                    seatDtoList.stream().map(SeatDto::getTicketCategoryId).distinct().sorted().collect(Collectors.toList());
        }else {
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }
        // 第一轮：构造两层锁列表（本地 ReentrantLock + 分布式 Redisson RLock），此时尚未申请锁
        List<ReentrantLock> localLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<RLock> serviceLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<ReentrantLock> localLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        List<RLock> serviceLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        for (Long ticketCategoryId : ticketCategoryIdList) {
            String lockKey = StrUtil.join("-",PROGRAM_ORDER_CREATE_V2,
                    programOrderCreateDto.getProgramId(),ticketCategoryId);
            ReentrantLock localLock = localLockCache.getLock(lockKey,false);
            RLock serviceLock = serviceLockTool.getLock(LockType.Reentrant, PROGRAM_ORDER_CREATE_V2,
                    new String[]{String.valueOf(programOrderCreateDto.getProgramId()), String.valueOf(ticketCategoryId)});
            localLockList.add(localLock);
            serviceLockList.add(serviceLock);
        }
        // 第二轮：获取所有本地锁（限时等待），任一失败标记 localLockFail 并停止获取
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
        // 第三轮：本地锁全部获取成功后才获取分布式锁，任一失败标记 serviceLockFail 并 break
        boolean serviceLockFail = false;
        if (!localLockFail) {
            for (RLock rLock : serviceLockList) {
                try {
                    if (rLock.tryLock(LOCK_WAIT_TIME, TimeUnit.SECONDS)) {
                        serviceLockSuccessList.add(rLock);
                    } else {
                        serviceLockFail = true;
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    serviceLockFail = true;
                    break;
                }
            }
        }
        try {
            // 任一层锁未全部获取成功时提前抛出异常（不执行下单逻辑）
            if (localLockFail || serviceLockFail) {
                throw new TicketFlowFrameException(BaseCode.SERVICE_LOCK_FAIL);
            }
            return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V21_VERSION.getValue());
        }finally {
            // 反向释放：先分布式锁（serviceLock），后本地锁（localLock），避免锁交叉依赖死锁
            for (int i = serviceLockSuccessList.size() - 1; i >= 0; i--) {
                RLock rLock = serviceLockSuccessList.get(i);
                try {
                    rLock.unlock();
                }catch (Exception e) {
                    log.error("service lock unlock error",e);
                }
            }
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
