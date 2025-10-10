package com.ticketflow.context;

/**
 * 上下文获取接口（策略模式）。
 * 适配两种 Web 容器：
 *   GatewayContextHandler — Spring Cloud Gateway（Reactive）
 *   WebMvcContextHandler — Servlet（Tomcat）
 *
 * 从当前请求的 header 中获取灰度标识等上下文参数，
 * 用于 ServerGrayFilter 的路由决策
 */
public interface ContextHandler {
    
    /***
     * 从request请求头获取值
     * @param name 值的名
     * @return 具体值
     * 
     */
    String getValueFromHeader(String name);
}