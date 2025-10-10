package com.ticketflow.initialize.impl.composite;

import com.ticketflow.initialize.impl.composite.init.CompositeInit;
import org.springframework.context.annotation.Bean;

/**
 * 组合模式初始化执行器配置。
 * 将 CompositeContainer 注册为 Spring Bean，自动注入所有 AbstractComposite 实现，
 * 按 order 排序构建 BFS 执行树。
 *
 * 启动时 CompositeInit 根据 InitializeHandlerType 分类执行初始化：
 *   PostConstruct 类型 → 立即执行
 *   InitializingBean 类型 → Spring 容器触发
 *   CommandLineRunner 类型 → 启动后触发
 *   ApplicationStartedEvent 类型 → 事件驱动
 */
public class CompositeAutoConfiguration {
    
    @Bean
    public CompositeContainer compositeContainer(){
        return new CompositeContainer();
    }
    
    @Bean
    public CompositeInit compositeInit(CompositeContainer compositeContainer){
        return new CompositeInit(compositeContainer);
    }
}
