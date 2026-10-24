package com.ticketflow.feign;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 内部令牌透传自动配置。
 * <p>
 * 放在 client 模块里而不是 service-component：只有调用 base-data 的服务才需要这个拦截器，
 * 而 service-component 是公共框架模块，不应该反向依赖某个具体 client。
 * 任何依赖本模块的服务（gateway / user-service / base-data-service ...）都会自动装配。
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class InternalTokenFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InternalTokenFeignInterceptor internalTokenFeignInterceptor(
            @Value("${ticketflow.internal.token:}") String internalToken) {
        return new InternalTokenFeignInterceptor(internalToken);
    }
}
