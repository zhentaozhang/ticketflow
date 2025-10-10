package com.ticketflow.initialize.base;

import org.springframework.boot.CommandLineRunner;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_COMMAND_LINE_RUNNER;
import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_POST_CONSTRUCT;

/**
 * CommandLineRunner 类型初始化抽象基类。
 * 子类在 ApplicationContext 刷新之后、main 方法执行前运行
 */
public abstract class AbstractApplicationCommandLineRunnerHandler implements InitializeHandler {
    
    @Override
    public String type() {
        return APPLICATION_COMMAND_LINE_RUNNER;
    }
}
