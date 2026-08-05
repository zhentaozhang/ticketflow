package com.ticketflow.initialize.execute;

import com.ticketflow.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ConfigurableApplicationContext;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_INITIALIZING_BEAN;

/**
 * {@link InitializingBean} 初始化执行器。在Bean属性设置完成后执行初始化逻辑。
 **/

public class ApplicationInitializingBeanExecute extends AbstractApplicationExecute implements InitializingBean {

    public ApplicationInitializingBeanExecute(ConfigurableApplicationContext applicationContext) {
        super(applicationContext);
    }

    @Override
    public void afterPropertiesSet() {
        execute();
    }

    @Override
    public String type() {
        return APPLICATION_INITIALIZING_BEAN;
    }
}
