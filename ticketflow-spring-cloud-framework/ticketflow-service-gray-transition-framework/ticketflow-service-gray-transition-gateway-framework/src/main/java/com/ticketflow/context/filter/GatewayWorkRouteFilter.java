package com.ticketflow.context.filter;


import com.ticketflow.context.impl.GatewayContextHolder;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 灰度上下文数据存放过滤器（最高优先级）。
 * 在第一个过滤器执行前将 ServerWebExchange 存入 GatewayContextHolder，
 * 供下游灰度路由逻辑读取请求元数据（header / path 等）。
 * <p>
 * 与 GatewayWorkClearFilter（最低优先级）配对使用，
 * 形成灰度上下文在 Gateway 请求全生命周期的 thread-local 管理。
 */
public class GatewayWorkRouteFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        GatewayContextHolder.getCurrentGatewayContext().setExchange(exchange);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}
