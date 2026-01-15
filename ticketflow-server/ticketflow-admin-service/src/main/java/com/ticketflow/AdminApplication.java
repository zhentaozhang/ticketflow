package com.ticketflow;

import com.ticketflow.config.TicketFlowCommonAutoConfig;
import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
/**
 * Spring Boot Admin 监控服务启动入口。
 * 通过注册中心发现所有服务实例，提供实时的健康检查、JVM 监控、
 * 日志级别动态调整等运维能力
 */
@EnableAdminServer
@EnableDiscoveryClient
@SpringBootApplication(exclude = TicketFlowCommonAutoConfig.class)
public class AdminApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(AdminApplication.class, args);
    }

}
