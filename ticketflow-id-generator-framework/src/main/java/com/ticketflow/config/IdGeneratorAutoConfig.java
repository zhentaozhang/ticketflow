package com.ticketflow.config;

import com.ticketflow.toolkit.SnowflakeIdGenerator;
import com.ticketflow.toolkit.WorkAndDataCenterIdHandler;
import com.ticketflow.toolkit.WorkDataCenterId;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式ID自动配置。初始化雪花算法ID生成器及相关Bean。
 */
public class IdGeneratorAutoConfig {

    @Bean
    public WorkAndDataCenterIdHandler workAndDataCenterIdHandler(StringRedisTemplate stringRedisTemplate) {
        return new WorkAndDataCenterIdHandler(stringRedisTemplate);
    }

    @Bean
    public WorkDataCenterId workDataCenterId(WorkAndDataCenterIdHandler workAndDataCenterIdHandler) {
        return workAndDataCenterIdHandler.getWorkAndDataCenterId();
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(WorkDataCenterId workDataCenterId) {
        return new SnowflakeIdGenerator(workDataCenterId);
    }
}
