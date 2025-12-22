package com.ticketflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
/**
 * 网关服务启动入口。
 * 路由转发、请求防重放、限流降级、鉴权认证、
 * 灰度发布、参数传播等全部在此层完成
 **/
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(GatewayApplication.class, args);
    }

}
