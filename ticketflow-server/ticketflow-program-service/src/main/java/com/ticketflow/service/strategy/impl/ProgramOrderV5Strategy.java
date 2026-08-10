package com.ticketflow.service.strategy.impl;

import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * V5 订单创建策略——无锁 + fire-and-forget 异步提交。
 *
 * 与 V4 的差异：
 * - 去掉应用层本地锁（并发安全由 Lua 原子扣减兜底，消除 70005 锁竞争失败）
 * - Kafka 发送不等待确认（fire-and-forget，立即返回订单号），
 *   请求线程不被 Kafka RTT 占用，吞吐拐点最高
 * 最终一致性由消息对账（ReconciliationTask）+ 延迟取消队列兜底。
 **/
@Slf4j
@Component
public class ProgramOrderV5Strategy implements ProgramOrderStrategy {

    @Autowired
    private ProgramOrderService programOrderService;

    @Autowired
    private CompositeContainer compositeContainer;

    /**
     * 创建订单（V5 无锁 fire-and-forget 版本）
     * 校验链 → Lua 原子扣减（无锁）→ 提交 Kafka（不等待确认）立即返回订单号
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
        return programOrderService.createNewAsyncFireAndForget(programOrderCreateDto, ProgramOrderVersion.V5_VERSION.getValue());
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
