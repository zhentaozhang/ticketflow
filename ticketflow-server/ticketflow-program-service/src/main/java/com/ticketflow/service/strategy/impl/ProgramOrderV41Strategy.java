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
 * 节目订单 V4 实现（Kafka 异步版本）。
 * 调用 createNewAsync() 将订单创建请求发送到 Kafka 异步处理。
 * 客户端轮询订单状态完成最终一致。
 *
 * V41 是 V4 的变体，与 V4 共享异步消息通道但使用不同的消费端逻辑。
 * 适用于高并发场景下削峰填谷。
 */
@Slf4j
@Component
public class ProgramOrderV41Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private BaseProgramOrder baseProgramOrder;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    /**
     * 创建订单（V41 异步变体版本）
     * 直接调用 createNewAsync() 走 Kafka 异步消费，适用于高并发削峰场景
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
        return programOrderService.createNewAsync(programOrderCreateDto,ProgramOrderVersion.V41_VERSION.getValue());
    }
    
    /**
     * 获取版本号
     *
     * @return V41_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V41_VERSION.getVersion();
    }
}
