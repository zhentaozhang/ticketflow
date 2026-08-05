package com.ticketflow.context.config;

import com.ticketflow.context.ContextHandler;
import com.ticketflow.context.filter.GatewayWorkClearFilter;
import com.ticketflow.context.filter.GatewayWorkRouteFilter;
import com.ticketflow.context.impl.GatewayContextHandler;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;

/**
 * Gateway 灰度上下文自动配置。
 * 注册 GatewayContextHolder 和 GatewayContextHandler Bean
 */
public class GatewayContextAutoConfiguration {
    
    @Bean
    public GlobalFilter gatewayWorkRouteFilter() {
        return new GatewayWorkRouteFilter();
    }
    
    @Bean
    public GlobalFilter gatewayWorkClearFilter() {
        return new GatewayWorkClearFilter();
    }
    
    @Bean
    public ContextHandler gatewayContextHandler(){
        return new GatewayContextHandler();
    }
}
