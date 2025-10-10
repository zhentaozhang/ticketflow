package com.ticketflow.initialize.base;

import jakarta.annotation.PostConstruct;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_POST_CONSTRUCT;

/**
 * PostConstruct 类型初始化抽象基类。
 * 子类在 executeInit 中加载 Lua 脚本或预热缓存，
 * 在构造函数阶段自动调用
 */
public abstract class AbstractApplicationPostConstructHandler implements InitializeHandler {

    @Override
    public String type() {
        return APPLICATION_POST_CONSTRUCT;
    }
}
