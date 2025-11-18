package com.baidu.fsg.uid.config;

import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * ID生成器Redis适配。为百度UID生成器提供Redis依赖，配置RedisTemplate用于WorkerId管理。
 * 参考: https://github.com/baidu/uid-generator/
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.data.redis.host")
public class IdGeneratorRedisConfig {
    
    @Bean("idGeneratorRedisTemplate")
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate redisTemplate = new RedisTemplate();
        redisTemplate.setDefaultSerializer(new StringRedisSerializer());
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        return redisTemplate;
    }
    
    @Bean("disposableWorkerIdAssigner")
    public WorkerIdAssigner redisDisposableWorkerIdAssigner(@Qualifier("idGeneratorRedisTemplate") RedisTemplate redisTemplate){
        RedisDisposableWorkerIdAssigner redisDisposableWorkerIdAssigner = new RedisDisposableWorkerIdAssigner(redisTemplate);
        return redisDisposableWorkerIdAssigner;
    }
}
