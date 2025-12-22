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
 * Token 解析与用户数据获取。
 * 流程：JWT 解码（TokenUtil.parseToken）→ 提取 userId → Redis 查询 UserVo
 *        → 不存在则抛出 LOGIN_USER_NOT_EXIST
 *
 * Token 由 user-service 在登录时签发，存储为 user_login_{code}_{userId}。
 * Gateway 层通过此服务在请求入口验证登录态，将 userId 注入 header 透传下游。
 */

@Component
public class TokenService {
    
    @Autowired
    private RedisCache redisCache;
    
    public String parseToken(String token,String tokenSecret){
        String userStr = TokenUtil.parseToken(token,tokenSecret);
        if (StringUtil.isNotEmpty(userStr)) {
            return JSONObject.parseObject(userStr).getString("userId");
        }
        return null;
    }
    
    public UserVo getUser(String token,String code,String tokenSecret){
        UserVo userVo = null;
        String userId = parseToken(token,tokenSecret);
        if (StringUtil.isNotEmpty(userId)) {
            userVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, userId), UserVo.class);
        }
        return Optional.ofNullable(userVo).orElseThrow(() -> new TicketFlowFrameException(BaseCode.LOGIN_USER_NOT_EXIST));
    }
}
