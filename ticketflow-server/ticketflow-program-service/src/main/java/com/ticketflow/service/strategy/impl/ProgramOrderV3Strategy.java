package com.ticketflow.service.strategy.impl;

import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.strategy.BaseProgramOrder;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V3;

/**
 * V3 订单创建策略——抽取公共加锁逻辑。
 *
 * V2 的手动加锁逻辑被抽取到 BaseProgramOrder.localLockCreateOrder()，
 * V3 策略类只负责编排：复合校验 → 本地锁（按 ticketCategoryId）→ 同步创建。
 *
 * 相比 V2，加锁逻辑可复用（V4 也使用相同的 BaseProgramOrder）。
 **/
@Slf4j
@Component
public class ProgramOrderV3Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private BaseProgramOrder baseProgramOrder;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    /**
     * 创建订单（V3 公共锁模板版本）
     * 委托 BaseProgramOrder.localLockCreateOrder 管理锁，策略类仅编排校验与下单回调
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
        return baseProgramOrder.localLockCreateOrder(PROGRAM_ORDER_CREATE_V3,programOrderCreateDto,
                () -> programOrderService.createNew(programOrderCreateDto,ProgramOrderVersion.V3_VERSION.getValue()));
    }
    
    /**
     * 获取版本号
     *
     * @return V3_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V3_VERSION.getVersion();
    }
}
