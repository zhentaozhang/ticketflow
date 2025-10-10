package com.ticketflow.context.config;

import com.ticketflow.context.ContextHandler;
import com.ticketflow.context.impl.WebMvcContextHandler;
import org.springframework.context.annotation.Bean;

/**
 * WebMvc 灰度上下文自动配置。
 * 注册 WebMvcContextHandler Bean，为下游服务提供灰度标记存取
 */
public class WebMvcContextAutoConfiguration {

    @Bean
    public ContextHandler webMvcContext() {
        return new WebMvcContextHandler();
    }
}
