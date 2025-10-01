package com.ticketflow.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * 公共自动配置。
 * 注册 Jackson 日期序列化自定义配置（jacksonCustom），
 * 被所有引用 ticketflow-common 的服务自动加载
 */

public class TicketFlowCommonAutoConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustom() {
        return new JacksonCustom();
    }
}
