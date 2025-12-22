package com.ticketflow.service.strategy.impl;

import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import com.ticketflow.servicelock.annotion.ServiceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V1;

/**
 * V1 订单创建策略——最简单，性能最低。
 *
 * 使用 @ServiceLock 注解在方法级别对整个节目（programId）加锁，
 * 粒度最粗，同一节目的所有订单创建串行化。
 *
 * @RepeatExecuteLimit 双层防重（本地锁 → Redis Fair Lock）
 * @ServiceLock        分布式锁（方法级）
 * → 最终调用 ProgramOrderService.create() 同步创建
 **/
@Component
public class ProgramOrderV1Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    
    /**
     * 创建订单（V1 粗粒度锁版本）
     * 双重防重（@RepeatExecuteLimit）+ 方法级分布式锁（@ServiceLock）→ 同步 DB 写入
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号
     */
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @ServiceLock(name = PROGRAM_ORDER_CREATE_V1,keys = {"#programOrderCreateDto.programId"})
    @Override
    public String createOrder(final ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V1_VERSION.getValue());
    }
    
    /**
     * 获取版本号
     *
     * @return V1_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V1_VERSION.getVersion();
    }
}
