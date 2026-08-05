package com.ticketflow.config;

import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.service.CaptchaService;
import com.ticketflow.captcha.service.impl.CaptchaCacheServiceMemImpl;
import com.ticketflow.captcha.util.ImageUtils;
import com.ticketflow.properties.AjCaptchaProperties;
import com.ticketflow.service.CaptchaCacheServiceRedisImpl;
import com.ticketflow.service.CaptchaHandle;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 验证码自动配置。注册验证码缓存服务与验证码处理器，并在启动时加载验证码底图。
 **/
@Configuration
@EnableConfigurationProperties(AjCaptchaProperties.class)
public class CaptchaAutoConfig {

    private final AjCaptchaProperties config;

    public CaptchaAutoConfig(AjCaptchaProperties config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        ImageUtils.cacheImage(config.getJigsaw(), config.getPicClick());
    }

    @Bean
    public CaptchaHandle captchaHandle(List<CaptchaService> captchaServices) {
        CaptchaService captchaService = findService(captchaServices, config.getType().getCodeValue());
        if (captchaService == null) {
            captchaService = findService(captchaServices, "default");
        }
        return new CaptchaHandle(captchaService);
    }

    private CaptchaService findService(List<CaptchaService> captchaServices, String captchaType) {
        for (CaptchaService captchaService : captchaServices) {
            if (captchaType.equals(captchaService.captchaType())) {
                return captchaService;
            }
        }
        return null;
    }

    @Bean(name = "AjCaptchaCacheService")
    @Primary
    public CaptchaCacheService captchaCacheService(StringRedisTemplate redisTemplate) {
        if (AjCaptchaProperties.StorageType.redis.equals(config.getCacheType())) {
            CaptchaCacheServiceRedisImpl redis = new CaptchaCacheServiceRedisImpl();
            redis.setStringRedisTemplate(redisTemplate);
            return redis;
        }
        return new CaptchaCacheServiceMemImpl();
    }
}
