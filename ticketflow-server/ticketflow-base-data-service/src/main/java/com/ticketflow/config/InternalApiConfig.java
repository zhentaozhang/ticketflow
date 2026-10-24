package com.ticketflow.config;

import com.ticketflow.interceptor.InternalApiInterceptor;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部接口鉴权配置：把 {@link InternalApiInterceptor} 挂到全部路径上。
 * <p>
 * 拦截器只对 @InternalApi 标注的处理器生效，普通业务接口行为不变。
 */
@Slf4j
@Configuration
public class InternalApiConfig implements WebMvcConfigurer {

    private static final String DEV_DEFAULT_INTERNAL_TOKEN = "ticketflow-dev-internal-token";

    @Value("${ticketflow.internal.token:}")
    private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (StringUtil.isEmpty(internalToken)) {
            log.error("ticketflow.internal.token 未配置：所有 @InternalApi 接口都会被拒绝（fail closed），"
                    + "请通过环境变量 INTERNAL_TOKEN 注入与调用方一致的令牌");
        } else if (DEV_DEFAULT_INTERNAL_TOKEN.equals(internalToken)) {
            log.warn("ticketflow.internal.token 使用开发默认值，生产环境必须通过 INTERNAL_TOKEN 覆盖");
        }
        registry.addInterceptor(new InternalApiInterceptor(internalToken)).addPathPatterns("/**");
    }
}
