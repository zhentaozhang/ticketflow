package com.ticketflow.service.strategy.impl;

import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.domain.CreateOrderTemporaryData;
import com.ticketflow.service.strategy.BaseProgramOrder;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V5;

/**
 * V5 订单创建策略——V4 的独立注册变体（基线拷贝）。
 *
 * 与 V4 采用相同的编排：复合校验 → BaseProgramOrder 本地锁（按 ticketCategoryId）→
 * 锁内 Lua 原子扣减 → 锁外 createNewAsyncAfterLock() 发 Kafka 异步建单。
 * 用于后续单机优化的对照基线。
 */
@Slf4j
@Component
public class ProgramOrderV5Strategy implements ProgramOrderStrategy {

    @Autowired
    private ProgramOrderService programOrderService;

    @Autowired
    private BaseProgramOrder baseProgramOrder;

    @Autowired
    private CompositeContainer compositeContainer;

    /**
     * 创建订单（V5 异步版本，V4 基线拷贝）
     * 锁内仅执行 Lua 原子扣减（余票/座位），锁外再发送 Kafka 建单消息。
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号
     */
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId", "#programOrderCreateDto.programId"})
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(), programOrderCreateDto);
        CreateOrderTemporaryData createOrderTemporaryData = baseProgramOrder.localLockExecute(
                PROGRAM_ORDER_CREATE_V5, programOrderCreateDto,
                () -> programOrderService.createOrderOperateProgramCacheResolution(programOrderCreateDto));
        return programOrderService.createNewAsyncAfterLock(programOrderCreateDto, createOrderTemporaryData,
                ProgramOrderVersion.V5_VERSION.getValue());
    }

    /**
     * 获取版本号
     *
     * @return V5_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V5_VERSION.getVersion();
    }
}
