package com.ticketflow.context.impl;

import com.ticketflow.context.ContextHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * Gateway 上下文实现。
 * 从 ServerWebExchange 的 attributes 中读写灰度标记，
 * 适配 WebFlux 非阻塞模型
 */
public class GatewayContextHandler implements ContextHandler {
    @Override
    public String getValueFromHeader(final String name) {
        return Optional.ofNullable(getServerHttpRequest())
                .map(request -> request.getHeaders().getFirst(name))
                .orElse(null);
    }
    
    public ServerWebExchange getExchange() {
        return GatewayContextHolder.getCurrentGatewayContext().getExchange();
    }
    
    public ServerHttpRequest getServerHttpRequest() {
        return Optional.ofNullable(getExchange()).map(ServerWebExchange::getRequest).orElse(null);
    }
}
