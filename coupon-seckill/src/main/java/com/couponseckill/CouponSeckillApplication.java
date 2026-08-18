package com.couponseckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优惠券秒杀独立单体应用入口。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.couponseckill.mapper")
public class CouponSeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouponSeckillApplication.class, args);
    }
}
