package com.ticketflow.lockinfo.factory;


import com.ticketflow.lockinfo.LockInfoHandle;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 锁信息处理器工厂——按类型名从 Spring 容器获取对应的 LockInfoHandle。
 *
 * 相当于一个简易 SPI：不同场景注册不同名称的 Bean，
 *   LockInfoType.SERVICE_LOCK          → ServiceLockInfoHandle（@ServiceLock 用）
 *   LockInfoType.REPEAT_EXECUTE_LIMIT  → RepeatExecuteLimitLockInfoHandle（@RepeatExecuteLimit 用）
 *
 * 两个 Handle 使用不同的锁名前缀，防止锁名冲突。
 * 通过 ApplicationContextAware 注入容器，而不是 @Autowired，
 * 是因为这个类在 AutoConfiguration 中作为 @Bean 创建，
 * 此时 BeanFactory 可能还未完成所有后置处理器的注册。
 **/
public class LockInfoHandleFactory implements ApplicationContextAware {
    
    private ApplicationContext applicationContext;

    public LockInfoHandle getLockInfoHandle(String lockInfoType){
        return applicationContext.getBean(lockInfoType,LockInfoHandle.class);
    }
    
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
