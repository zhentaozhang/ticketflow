package com.ticketflow.environment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * EnvironmentPostProcessor：应用启动时设置 allowBeanDefinitionOverriding=true，
 * 允许同名 Bean 覆盖（框架模块中的配置类可能被业务模块中的同类重新声明）
 */
public class SpringEnvironment implements EnvironmentPostProcessor {
    
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        application.setAllowBeanDefinitionOverriding(true);
    }
}
