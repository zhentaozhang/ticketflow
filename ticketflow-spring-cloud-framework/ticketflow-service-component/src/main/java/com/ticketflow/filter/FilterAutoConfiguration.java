package com.ticketflow.filter;

import cn.dev33.satoken.config.SaTokenConfig;
import com.ticketflow.properties.ApiVerify;
import com.ticketflow.properties.BackManageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet 过滤器链自动配置。
 * 注册 BackManageAuthFilter（后台认证）、
 * RequestWrapperFilter（请求体缓存）、
 * BaseParameterFilter（上下文参数透传）、
 * SkyWalkingFilter（链路追踪）
 */
@EnableConfigurationProperties(BackManageProperties.class)
public class FilterAutoConfiguration {

    @Bean
    @Order(-11)
    public OncePerRequestFilter backManageAuthFilter(BackManageProperties backManageProperties) {
        return new BackManageAuthFilter(backManageProperties);
    }

    @Bean
    @Order(-10)
    public RequestWrapperFilter requestWrapperFilter() {
        return new RequestWrapperFilter();
    }

    @Bean
    @Order(1)
    public BaseParameterFilter baseParameterFilter() {
        return new BaseParameterFilter();
    }

    @Bean
    @Order(-1)
    public SkyWalkingFilter skyWalkingFilter() {
        return new SkyWalkingFilter();
    }

    @Bean
    @Primary
    public SaTokenConfig getSaTokenConfigPrimary() {
        SaTokenConfig config = new SaTokenConfig();
        // token 名称（同时也是 cookie 名称）
        config.setTokenName("satoken");
        // token 有效期（单位：秒），默认30天，这里改成1天，-1代表永不过期 
        config.setTimeout(24 * 60 * 60);
        // token 最低活跃频率（单位：秒），如果 token 超过此时间没有访问系统就会被冻结，默认-1 代表不限制，永不冻结
        config.setActiveTimeout(-1);
        // 是否允许同一账号多地同时登录（为 true 时允许一起登录，为 false 时新登录挤掉旧登录）
        config.setIsConcurrent(true);
        // 在多人登录同一账号时，是否共用一个 token （为 true 时所有登录共用一个 token，为 false 时每次登录新建一个 token）
        config.setIsShare(true);
        // token 风格
        config.setTokenStyle("uuid");
        // 是否输出操作日志 
        config.setIsLog(false);
        return config;
    }

    @Bean
    public ApiVerify apiVerify(BackManageProperties backManageProperties) {
        return new ApiVerify(backManageProperties);
    }
}
