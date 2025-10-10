package com.ticketflow.feign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import static com.ticketflow.constant.Constant.SERVER_GRAY;


/**
 * Feign 扩展插件自动配置。
 * 注册 FeignRequestInterceptor，在服务间调用时传播
 * userId、channel、grayVersion 等请求上下文参数
 */

public class ExtraFeignAutoConfiguration {

    @Value(SERVER_GRAY)
    public String serverGray;

    @Bean
    public FeignRequestInterceptor feignRequestInterceptor() {
        return new FeignRequestInterceptor(serverGray);
    }
}
