package com.ticketflow.initialize.base;

import org.springframework.beans.factory.InitializingBean;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_INITIALIZING_BEAN;

/**
 * InitializingBean 类型初始化抽象基类。
 * 子类在所有 Bean 属性注入完成后、Bean 正式使用前执行
 */
public abstract class AbstractApplicationInitializingBeanHandler implements InitializeHandler {

    @Override
    public String type() {
        return APPLICATION_INITIALIZING_BEAN;
    }
}
