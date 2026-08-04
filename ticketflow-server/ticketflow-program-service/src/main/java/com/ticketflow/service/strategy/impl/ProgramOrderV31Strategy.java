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
 * 节目订单 V31 实现（V3 的独立注册变体）。
 * 与 V3 相同的编排：复合校验 → BaseProgramOrder 本地锁（按 ticketCategoryId）→ createNew() 同步建单。
 * 并发安全统一由带校验的 Lua + 本地锁保证，与 V3 行为一致。
 */
@Slf4j
@Component
public class ProgramOrderV31Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private BaseProgramOrder baseProgramOrder;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    /**
     * 创建订单（V31 同步委托版本）
     * 委托 BaseProgramOrder.localLockCreateOrder 管理本地锁，回调 createNew() 同步写 DB
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
                () -> programOrderService.createNew(programOrderCreateDto,ProgramOrderVersion.V31_VERSION.getValue()));
    }
    
    /**
     * 获取版本号
     *
     * @return V31_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V31_VERSION.getVersion();
    }
}
