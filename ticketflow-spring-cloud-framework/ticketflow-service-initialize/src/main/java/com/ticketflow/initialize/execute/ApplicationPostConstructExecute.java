package com.ticketflow.initialize.execute;

import com.ticketflow.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.context.ConfigurableApplicationContext;

import jakarta.annotation.PostConstruct;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_POST_CONSTRUCT;

/**
 * {@link PostConstruct} 初始化执行器。在Bean初始化完成后执行标有@PostConstruct的初始化方法。
 **/
public class ApplicationPostConstructExecute extends AbstractApplicationExecute {
    
    public ApplicationPostConstructExecute(ConfigurableApplicationContext applicationContext){
        super(applicationContext);
    }
    
    @PostConstruct
    public void postConstructExecute() {
        execute();
    }
    
    @Override
    public String type() {
        return APPLICATION_POST_CONSTRUCT;
    }
}
