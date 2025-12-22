package com.ticketflow.service.strategy;

import com.ticketflow.dto.ProgramOrderCreateDto;

/**
 * 节目订单创建策略接口。
 *
 * 存在 V1 / V2 / V3 / V4 四个实现，通过 version() 返回值匹配 ProgramOrderVersion。
 * ProgramOrderController 根据客户端传入的版本号路由到对应的策略。
 *
 * 四个版本的锁策略演化：
 *   V1 — @ServiceLock 注解，粗粒度整节目加锁，实现最简单但并发最低
 *   V2 — 手动加锁，按 ticketCategoryId 拆锁，细粒度本地锁 + 分布式锁
 *   V3 — 提取公共 BaseProgramOrder.localLockCreateOrder()，策略类只负责编排
 *   V4 — 异步 + Kafka，createNewAsync 将订单创建发往 Kafka，性能最高
 **/
public interface ProgramOrderStrategy {
    
    /**
     * 创建订单
     * @param programOrderCreateDto 订单参数
     * @return 订单编号
     * */
    String createOrder(ProgramOrderCreateDto programOrderCreateDto);
    
    /**
     * 获取版本号
     * @return 版本号
     * */
    String version();
}
