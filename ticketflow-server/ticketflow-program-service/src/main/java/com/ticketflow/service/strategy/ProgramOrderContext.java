package com.ticketflow.service.strategy;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单创建策略上下文（策略模式）。
 * 启动时扫描所有 ProgramOrderStrategy 实现，按 version() 注册到 MAP。
 * 客户端通过 version 参数选择策略（V1/V2/V3/V4/V21/V31/V41）。
 *
 * 扩展方式：新增 ProgramOrderStrategy 实现并标注 @Component，
 *           自动注入 strategyList，无需修改此文件（满足开闭原则）
 */
@Component
public class ProgramOrderContext {
    
    private static final Map<String,ProgramOrderStrategy> MAP = new HashMap<>(8);
    
    @Autowired
    private List<ProgramOrderStrategy> programOrderStrategyList;
    
    /**
     * 启动注册：将所有 ProgramOrderStrategy 实现按 version() 注册到 MAP
     */
    @PostConstruct
    public void init() {
        for (ProgramOrderStrategy programOrderStrategy : programOrderStrategyList) {
            MAP.put(programOrderStrategy.version(), programOrderStrategy);
        }
    }
    
    /**
     * 根据版本号获取对应的订单创建策略
     *
     * @param version 策略版本号（如 V1、V2、V3、V4）
     * @return 匹配的订单创建策略
     * @throws TicketFlowFrameException 当版本号不存在时抛出 PROGRAM_ORDER_STRATEGY_NOT_EXIST
     */
    public ProgramOrderStrategy get(String version){
        return Optional.ofNullable(MAP.get(version)).orElseThrow(() -> 
                new TicketFlowFrameException(BaseCode.PROGRAM_ORDER_STRATEGY_NOT_EXIST));
    }
}
