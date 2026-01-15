package com.ticketflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 后台管理配置。包含管理端日志拦截、权限校验等配置。
 */

public class AdminConfig {
    
    @Primary
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomEnhance(){
        return new JacksonCustomEnhance();
    }
    
    @Bean
    public OpenAPI customOpenApi() {
        
        return new OpenAPI()
                .info(new Info()
                        .title("前端使用")
                        .version("1.0")
                        .description("项目学习")
                        .contact(new Contact()
                                .name("admin")
                        ));
        
    }
}
