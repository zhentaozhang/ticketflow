package com.ticketflow.service.strategy.impl;

import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * V5 订单创建策略——无锁 + Lua v2 幂等 + 本地库存闸门。
 *
 * 相比 V4（本地锁 + @RepeatExecuteLimit + V4 Lua + Kafka 异步建单）：
 * <ul>
 *   <li><b>无本地锁</b>：V4 的 localLockExecute 只是"快速失败"手段，正确性由 Lua 原子性保证；
 *       移除后请求只在 Redis 天然串行，消除 70005 锁竞争失败，吞吐曲线更平滑；</li>
 *   <li><b>幂等并入 Lua</b>：不标注 @RepeatExecuteLimit，幂等 SETNX 在 Lua v2 单脚本内与
 *       校验/扣减一起原子完成，请求侧每单仅 1 次 EVAL，不再需要独立的幂等 Redis 往返；</li>
 *   <li><b>本地库存闸门</b>：售罄请求在到达 Redis 前直接拒绝（售罄短路），零 Redis 消耗。</li>
 * </ul>
 * 建单仍走 Kafka 异步（createNewAsyncV5 → doCreateV2），请求线程不等待 DB 写入。
 **/
@Slf4j
@Component
public class ProgramOrderV5Strategy implements ProgramOrderStrategy {

    @Autowired
    private ProgramOrderService programOrderService;

    @Autowired
    private CompositeContainer compositeContainer;

    /**
     * 创建订单（V5 无锁 + Lua v2 幂等版本）
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号
     */
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(), programOrderCreateDto);
        return programOrderService.createNewAsyncV5(programOrderCreateDto);
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
