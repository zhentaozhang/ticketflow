package com.ticketflow.filter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 过滤器自动配置。注册 {@link RequestParamContextFilter}，
 * 将请求 Header 中的 traceId 写入 MDC 供日志链路追踪。
 * 注意：traceId 的 MDC 处理与 ticketflow-service-component 的 BaseParameterFilter 重叠（幂等），
 * 该过滤器在未引入 service-component 的服务中承担 traceId 透传
 **/
@AutoConfiguration
public class FilterConfig {

    @Bean
    public OncePerRequestFilter requestParamContextFilter(){
        return new RequestParamContextFilter();
    }
}
