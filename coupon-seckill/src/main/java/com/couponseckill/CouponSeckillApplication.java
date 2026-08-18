package com.couponseckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优惠券秒杀服务（独立单体验证 → 集成：注册 Nacos，服务名 ticketflow-coupon-service）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.couponseckill.mapper")
public class CouponSeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouponSeckillApplication.class, args);
    }
}
