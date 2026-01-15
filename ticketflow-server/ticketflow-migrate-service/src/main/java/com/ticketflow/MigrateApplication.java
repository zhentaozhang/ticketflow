package com.ticketflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 数据迁移服务启动入口。
 * 用于从旧系统/文件批量导入节目、座位等基础数据到新系统数据库
 */
@MapperScan({"com.ticketflow.mapper"})
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class MigrateApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(MigrateApplication.class, args);
    }

}
