package com.ticketflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API 数据服务启动入口。
 * 提供 API 埋点收采、消息发送/消费记录持久化、
 * 消息幂等控制等通用数据服务
 **/
@EnableScheduling
@MapperScan({"com.ticketflow.mapper"})
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class CustomizeApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(CustomizeApplication.class, args);
    }

}
