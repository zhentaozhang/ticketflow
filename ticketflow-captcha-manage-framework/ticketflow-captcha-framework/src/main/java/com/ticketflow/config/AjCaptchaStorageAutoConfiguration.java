package com.ticketflow.config;

import com.ticketflow.properties.AjCaptchaProperties;
import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.service.impl.CaptchaServiceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 验证码存储策略自动配置。根据配置选择验证码缓存存储方式。
 **/
@Configuration
public class AjCaptchaStorageAutoConfiguration {

    @Bean(name = "AjCaptchaCacheService")
    @ConditionalOnMissingBean
    public CaptchaCacheService captchaCacheService(AjCaptchaProperties config) {
        //缓存类型redis/local/....
        return CaptchaServiceFactory.getCache(config.getCacheType().name());
    }
}
