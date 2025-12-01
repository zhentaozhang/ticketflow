package com.ticketflow.config;

import com.ticketflow.properties.AjCaptchaProperties;
import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.service.CaptchaService;
import com.ticketflow.captcha.service.impl.CaptchaServiceFactory;
import com.ticketflow.service.CaptchaCacheServiceRedisImpl;
import com.ticketflow.service.CaptchaHandle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证码配置。封装验证码组件的配置属性，供自动配置类读取。
 **/
public class CaptchaAutoConfig {

    @Bean
    public CaptchaHandle captchaHandle(CaptchaService captchaService) {
        return new CaptchaHandle(captchaService);
    }

    @Bean(name = "AjCaptchaCacheService")
    @Primary
    public CaptchaCacheService captchaCacheService(AjCaptchaProperties config, StringRedisTemplate redisTemplate) {
        //缓存类型redis/local/....
        CaptchaCacheService ret = CaptchaServiceFactory.getCache(config.getCacheType().name());
        if (ret instanceof CaptchaCacheServiceRedisImpl) {
            ((CaptchaCacheServiceRedisImpl) ret).setStringRedisTemplate(redisTemplate);
        }
        return ret;
    }
}
