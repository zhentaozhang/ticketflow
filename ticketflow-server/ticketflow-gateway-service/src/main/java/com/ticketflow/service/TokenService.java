package com.ticketflow.service;

import com.alibaba.fastjson.JSONObject;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.redis.session.UserSessionStore;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.UserVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Token 解析与用户数据获取。
 * 流程：JWT 解码（TokenUtil.parseToken）→ 提取 userId → Redis 读取带签名的会话
 *        → 签名/归属校验通过才返回 UserVo，否则抛出 LOGIN_USER_NOT_EXIST
 *
 * Token 由 user-service 在登录时签发，会话存储在 user_login_{code}_{userId}。
 * 会话值本身带 HMAC 签名（{@link UserSessionStore}），因此「key 存在」不再等价于「登录态合法」：
 * 仅拿到 Redis 写权限、不知道签名密钥，也无法伪造一条可用会话。
 */

@Component
public class TokenService {

    @Autowired
    private UserSessionStore userSessionStore;

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
            Long userIdValue;
            try {
                userIdValue = Long.valueOf(userId);
            } catch (NumberFormatException e) {
                // token 里的 userId 非数字说明 token 被篡改或历史脏数据，按未登录处理
                throw new TicketFlowFrameException(BaseCode.LOGIN_USER_NOT_EXIST);
            }
            // 读取并校验会话签名，签名不合法时 loadPayload 返回 null
            userVo = userSessionStore.loadPayload(code, userIdValue, UserVo.class);
        }
        return Optional.ofNullable(userVo).orElseThrow(() -> new TicketFlowFrameException(BaseCode.LOGIN_USER_NOT_EXIST));
    }
}
