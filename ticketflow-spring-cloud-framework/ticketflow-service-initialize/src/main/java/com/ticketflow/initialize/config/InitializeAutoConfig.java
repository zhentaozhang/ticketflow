package com.ticketflow.initialize.config;

import com.ticketflow.initialize.execute.ApplicationCommandLineRunnerExecute;
import com.ticketflow.initialize.execute.ApplicationInitializingBeanExecute;
import com.ticketflow.initialize.execute.ApplicationPostConstructExecute;
import com.ticketflow.initialize.execute.ApplicationStartEventListenerExecute;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 初始化自动配置。注册应用程序启动各阶段的初始化执行器Bean。
 **/
public class InitializeAutoConfig {
    
    @Bean
    public ApplicationInitializingBeanExecute applicationInitializingBeanExecute(
            ConfigurableApplicationContext applicationContext){
        return new ApplicationInitializingBeanExecute(applicationContext);
    }
    
    @Bean
    public ApplicationPostConstructExecute applicationPostConstructExecute(
            ConfigurableApplicationContext applicationContext){
        return new ApplicationPostConstructExecute(applicationContext);
    }
    
    @Bean
    public ApplicationStartEventListenerExecute applicationStartEventListenerExecute(
            ConfigurableApplicationContext applicationContext){
        return new ApplicationStartEventListenerExecute(applicationContext);
    }
    
    @Bean
    public ApplicationCommandLineRunnerExecute applicationCommandLineRunnerExecute(
            ConfigurableApplicationContext applicationContext){
        return new ApplicationCommandLineRunnerExecute(applicationContext);
    }
}
