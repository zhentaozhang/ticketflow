package com.ticketflow.initialize.base;

import org.springframework.beans.factory.InitializingBean;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_EVENT_LISTENER;

/**
 * ApplicationStartedEvent 类型初始化抽象基类。
 * 子类在 Spring 容器完全启动后执行初始化
 */
public abstract class AbstractApplicationStartEventListenerHandler implements InitializeHandler {

    @Override
    public String type() {
        return APPLICATION_EVENT_LISTENER;
    }
}
