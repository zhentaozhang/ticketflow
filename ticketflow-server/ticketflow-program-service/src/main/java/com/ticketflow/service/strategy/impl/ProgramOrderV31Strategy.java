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

/**
 * 节目订单 V3 实现（BaseProgramOrder 委托版本）。
 * 核心逻辑提取到 BaseProgramOrder.create()，V3 策略仅做委托调用。
 * 锁逻辑由 BaseProgramOrder 统一管理（Lua 脚本 + 分布式锁），
 * V31 调用 createNew() 走同步 DB 回滚路径。
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
     * 直接调用 ProgramOrderService.createNew() 同步写 DB，锁由上游调用方管理
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
        return programOrderService.createNew(programOrderCreateDto,ProgramOrderVersion.V31_VERSION.getValue());
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
