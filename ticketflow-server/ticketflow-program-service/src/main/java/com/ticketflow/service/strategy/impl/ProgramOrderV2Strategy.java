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
 * V2 订单创建策略——细粒度锁。
 *
 * 放弃 @ServiceLock 的粗粒度加锁，改为手动按 ticketCategoryId 加锁：
 * 每个票价档位（ticketCategoryId）一把本地锁 + 一把 Redis 重入锁。
 *
 * 因为同一节目的不同票价档位可以独立下单，按类别拆分锁能提升并发。
 * 同时持有所有相关锁后，调用 create() 同步创建订单。
 *
 * 锁释放顺序与获取顺序相反（逆序释放），避免死锁。
 * 所有锁获取均带 3 秒超时，超时快速失败，避免锁持有者异常时无限阻塞。
 **/
@Slf4j
@Component
public class ProgramOrderV2Strategy implements ProgramOrderStrategy {

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
     * 创建订单（V2 细粒度锁版本）
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
        // 提取不重复的票价档位 ID，每个档位需要独立加锁（不同票价档可并发下单）
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
            RLock serviceLock = serviceLockTool.getLock(LockType.Reentrant, lockKey);
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
            return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V2_VERSION.getValue());
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
     * @return V2_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V2_VERSION.getVersion();
    }
}
