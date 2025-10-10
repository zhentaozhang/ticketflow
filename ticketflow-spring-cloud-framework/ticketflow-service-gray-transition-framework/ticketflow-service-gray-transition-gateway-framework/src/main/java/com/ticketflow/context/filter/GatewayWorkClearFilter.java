package com.ticketflow.context.filter;


import com.ticketflow.context.impl.GatewayContextHolder;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 灰度上下文数据清理过滤器（最低优先级 - 1）。
 * 在响应写完后移除 GatewayContextHolder 中的 thread-local 数据，
 * 防止请求处理结束后 ServerWebExchange 引用泄漏。
 *
 * 与 GatewayWorkRouteFilter（最高优先级）配对使用
 */
public class GatewayWorkClearFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        GatewayContextHolder.removeCurrentGatewayContext();
        return chain.filter(exchange);
    }
    
    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 1;
    }
}
