package com.ticketflow.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger配置。配置OpenAPI文档信息，包括接口标题、描述和联系方式。
 **/
@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenAPI customOpenApi() {

        return new OpenAPI()
                .info(new Info()
                        .title("前端使用")
                        .version("1.0")
                        .description("项目学习")
                        .contact(new Contact()
                                .name("..")
                        ));

    }
}

