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
 * <p>
 * 最高性能版本。与 V3 使用相同的 BaseProgramOrder.localLockCreateOrder()，
 * 但最终调用的是 ProgramOrderService.createNewAsync()：
 * 校验通过后 → 将订单创建消息发往 Kafka → 异步消费完成 DB 写入。
 * <p>
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
     *
     * <pre>
     * 防重 → 校验链 → 锁外预热缓存 → 锁内只做 Lua 原子扣减 → 锁外发 Kafka + 投递延迟取消
     * </pre>
     *
     * 两个“挪出临界区”的设计都是为了缩短锁持有时间：
     * 一是缓存预热（冷缓存时要读整张座位表，放在锁内会把后面的请求全部堵到 tryLock 超时），
     * 二是 Kafka 发送确认（网络操作，同步等待会放大锁竞争失败 70005）。
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号
     */
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId", "#programOrderCreateDto.programId"})  // ① 防重
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(), programOrderCreateDto); // ② 复合校验链
        // ③ 锁外：先确保座位/余票缓存就绪。冷缓存时这一步要读整张座位表写进 Redis，
        //    是重活；放在锁内会让第一批请求占满锁，后面的请求全部 tryLock(3s) 失败。
        programOrderService.ensureProgramCacheReady(programOrderCreateDto);
        CreateOrderTemporaryData createOrderTemporaryData = baseProgramOrder.localLockExecute(
                PROGRAM_ORDER_CREATE_V4, programOrderCreateDto,
                () -> programOrderService.createOrderOperateProgramCacheResolution(programOrderCreateDto)); // ④ 本地锁（按票档）内：只做 Lua 原子扣减
        return programOrderService.createNewAsyncAfterLock(programOrderCreateDto, createOrderTemporaryData,
                ProgramOrderVersion.V4_VERSION.getValue()); // ⑤ 锁外：发 Kafka + 投递延迟取消
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
