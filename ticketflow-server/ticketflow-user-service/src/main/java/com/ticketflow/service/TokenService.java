package com.ticketflow.service;

import com.alibaba.fastjson.JSONObject;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.util.StringUtil;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.UserVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JWT Token 解析 + Redis 用户数据查询。
 * 网关层 JWTFilter 解析出 userId 后存入 Redis，
 * 本服务在需要时通过 token 还原完整 UserVo 对象
 */

@Component
public class TokenService {

    private static final String TOKEN_SECRET = "***REMOVED***";

    @Autowired
    private RedisCache redisCache;

    public String parseToken(String token) {
        String userStr = TokenUtil.parseToken(token, TOKEN_SECRET);
        if (StringUtil.isNotEmpty(userStr)) {
            return JSONObject.parseObject(userStr).getString("userId");
        }
        return null;
    }

    public UserVo getUser(String token) {
        UserVo userVo = null;
        String userId = parseToken(token);
        if (StringUtil.isNotEmpty(userId)) {
            userVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, userId), UserVo.class);
        }
        return Optional.ofNullable(userVo).orElseThrow(() -> new TicketFlowFrameException(BaseCode.USER_EMPTY));
    }
}
