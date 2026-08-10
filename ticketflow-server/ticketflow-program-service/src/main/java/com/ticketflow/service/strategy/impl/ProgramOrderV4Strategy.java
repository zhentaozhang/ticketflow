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

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V4;

/**
 * V4 订单创建策略——异步 + Kafka。
 *
 * 最高性能版本。与 V3 使用相同的 BaseProgramOrder.localLockCreateOrder()，
 * 但最终调用的是 ProgramOrderService.createNewAsync()：
 *   校验通过后 → 将订单创建消息发往 Kafka → 异步消费完成 DB 写入。
 *
 * 同步路径（V1-V3）在 create() 中完成所有 DB 操作，RT 长；
 * 异步路径（V4）将写操作剥离到 Kafka 消费者，释放请求线程，RT 最短。
 **/
@Slf4j
@Component
public class ProgramOrderV4Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private BaseProgramOrder baseProgramOrder;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    /**
     * 创建订单（V4 异步 Kafka 版本）
     * 锁内仅执行 Lua 原子扣减（余票/座位），锁外再发送 Kafka 建单消息，
     * 避免同步等待 Kafka 发送确认拉长锁持有时间、放大锁竞争失败（70005）。
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
        CreateOrderTemporaryData createOrderTemporaryData = baseProgramOrder.localLockExecute(
                PROGRAM_ORDER_CREATE_V4, programOrderCreateDto,
                () -> programOrderService.createOrderOperateProgramCacheResolution(programOrderCreateDto));
        return programOrderService.createNewAsyncAfterLock(programOrderCreateDto, createOrderTemporaryData,
                ProgramOrderVersion.V4_VERSION.getValue());
    }
    
    /**
     * 获取版本号
     *
     * @return V4_VERSION 对应的版本标识
     */
    @Override
    public String version() {
        return ProgramOrderVersion.V4_VERSION.getVersion();
    }
}
