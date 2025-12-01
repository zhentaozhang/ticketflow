package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.model.common.Const;
import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.service.CaptchaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * 验证码服务工厂。根据配置创建对应类型的验证码服务实例。
 **/
public class CaptchaServiceFactory {

    private static Logger logger = LoggerFactory.getLogger(CaptchaServiceFactory.class);

    public static CaptchaService getInstance(Properties config) {
        //先把所有CaptchaService初始化，通过init方法，实例字体等
        String captchaType = config.getProperty(Const.CAPTCHA_TYPE, "default");
        CaptchaService ret = instances.get(captchaType);
        if (ret == null) {
            throw new RuntimeException("unsupported-[captcha.type]=" + captchaType);
        }
        ret.init(config);
        return ret;
    }

    public static CaptchaCacheService getCache(String cacheType) {
        return cacheService.get(cacheType);
    }

    public volatile static Map<String, CaptchaService> instances = new HashMap();
    public volatile static Map<String, CaptchaCacheService> cacheService = new HashMap();

    static {
        ServiceLoader<CaptchaCacheService> cacheServices = ServiceLoader.load(CaptchaCacheService.class);
        for (CaptchaCacheService item : cacheServices) {
            cacheService.put(item.type(), item);
        }
        logger.info("supported-captchaCache-service:{}", cacheService.keySet().toString());
        ServiceLoader<CaptchaService> services = ServiceLoader.load(CaptchaService.class);
        for (CaptchaService item : services) {
            instances.put(item.captchaType(), item);
        }
        ;
        logger.info("supported-captchaTypes-service:{}", instances.keySet().toString());
    }
}
