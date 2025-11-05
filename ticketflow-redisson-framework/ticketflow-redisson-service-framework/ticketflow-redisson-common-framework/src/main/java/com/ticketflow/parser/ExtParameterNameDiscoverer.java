package com.ticketflow.parser;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.NativeDetector;

/**
 * 扩展的 ParameterNameDiscoverer——在 Spring 默认实现基础上追加 LocalVariableTableParameterNameDiscoverer。
 *
 * 编译时需加上 -parameters 或 -g 参数才能从局部变量表获取参数名；
 * 用于 SpEL 表达式解析 #paramName → 实际参数值
 */
public class ExtParameterNameDiscoverer extends DefaultParameterNameDiscoverer {
    
    public ExtParameterNameDiscoverer() {
        super();
        if (!NativeDetector.inNativeImage()) {
            addDiscoverer(new LocalVariableTableParameterNameDiscoverer());
        }
    }
}
