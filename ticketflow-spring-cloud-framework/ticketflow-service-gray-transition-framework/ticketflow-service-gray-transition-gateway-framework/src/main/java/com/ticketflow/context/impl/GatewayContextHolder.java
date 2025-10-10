package com.ticketflow.context.impl;


import lombok.Getter;
import lombok.Setter;
import org.springframework.web.server.ServerWebExchange;

/**
 * Gateway 上下文持有者。
 * 使用 ThreadLocal 存储 ServerWebExchange，
 * 在网关过滤器中写入，供灰度过滤器从中提取灰度标记
 */
@Setter
@Getter
public class GatewayContextHolder {
    
    private static final ThreadLocal<GatewayContextHolder> THREAD_LOCAL = ThreadLocal.withInitial(GatewayContextHolder::new);

    private ServerWebExchange exchange;

    public static GatewayContextHolder getCurrentGatewayContext() {
        return THREAD_LOCAL.get();
    }

    public static void removeCurrentGatewayContext() {
        THREAD_LOCAL.remove();
    }
    
}