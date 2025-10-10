package com.ticketflow.initialize.execute;

import com.ticketflow.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_EVENT_LISTENER;

/**
 * {@link ApplicationStartedEvent} 初始化执行器。在Spring应用启动完成后执行初始化逻辑。
 **/
public class ApplicationStartEventListenerExecute extends AbstractApplicationExecute implements ApplicationListener<ApplicationStartedEvent> {
    
    public ApplicationStartEventListenerExecute(ConfigurableApplicationContext applicationContext){
        super(applicationContext);
    }
    
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        execute();
    }
    
    @Override
    public String type() {
        return APPLICATION_EVENT_LISTENER;
    }
}
