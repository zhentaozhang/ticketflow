package com.ticketflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 订单服务启动入口。
 * 聚合 Feign 声明式客户端，通过 @EnableFeignClients 发现上游服务接口。
 * 依赖服务：program-service（节目数据）、api-data-service（消息记录）
 */
@EnableScheduling
@MapperScan({"com.ticketflow.mapper"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE)
@SpringBootApplication
public class OrderApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(OrderApplication.class, args);
    }

}
