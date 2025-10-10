package com.ticketflow.initialize.base;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * 初始化执行顶层接口，应用启动时通过策略模式执行各类初始化逻辑。
 * type()       → 标识初始化类型（PostConstruct / StartEventListener 等）
 * executeOrder() → 同类型中的排序（值越小越优先）
 * executeInit()  → 实际初始化方法
 */
public interface InitializeHandler {
    /**
     * 初始化执行 类型
     * @return 类型
     * */
    String type();
    
    /**
     * 执行顺序
     * @return 顺序
     * */
    Integer executeOrder();
    
    /**
     * 执行逻辑
     * @param context 容器上下文
     * */
    void executeInit(ConfigurableApplicationContext context);
    
}
