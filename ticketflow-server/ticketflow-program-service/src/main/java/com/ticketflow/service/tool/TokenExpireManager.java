package com.ticketflow.service.tool;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token 过期时间配置。
 * 默认 40 分钟，用于 ProgramUserExistCheckHandler 校验用户登录态时
 * 作为 Redis 缓存 token 的时间窗口参考
 */
@Data
@Component
public class TokenExpireManager {
    
    @Value("${token.expire.time:40}")
    private Long tokenExpireTime;
}
