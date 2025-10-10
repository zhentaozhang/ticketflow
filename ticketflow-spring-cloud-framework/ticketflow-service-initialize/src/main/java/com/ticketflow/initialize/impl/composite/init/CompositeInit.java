package com.ticketflow.initialize.impl.composite.init;

import com.ticketflow.initialize.base.AbstractApplicationStartEventListenerHandler;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import lombok.AllArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CompositeContainer 初始化触发点。
 * 在应用启动后自动调用，驱动整个组合校验链的注册与执行
 */
@AllArgsConstructor
public class CompositeInit extends AbstractApplicationStartEventListenerHandler {
    
    private final CompositeContainer compositeContainer;
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(ConfigurableApplicationContext context) {
        compositeContainer.init(context);
    }
}
