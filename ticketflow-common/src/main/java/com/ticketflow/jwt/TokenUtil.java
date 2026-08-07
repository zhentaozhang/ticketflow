package com.ticketflow.jwt;

import com.alibaba.fastjson.JSONObject;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * JWT Token 编解码工具（HS256 签名）。
 * <p>
 * createToken(id, info, ttlMillis, secret) → 签发 JWT
 * parseToken(token, secret) → 解析 JWT，过期抛 TicketFlowFrameException(TOKEN_EXPIRE)
 * <p>
 * 注意：main 方法中的 tokenSecret 仅为测试使用，生产环境的密钥应从配置注入
 */
@Slf4j
public class TokenUtil {

    private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;

    public static String createToken(String id, String info, long ttlMillis, String tokenSecret) {

        long nowMillis = System.currentTimeMillis();


        JwtBuilder builder = Jwts.builder()
//                .setClaims(claims)
                .setId(id)
                .setIssuedAt(new Date(nowMillis))
                .setSubject(info)
                .signWith(SIGNATURE_ALGORITHM, tokenSecret);
        if (ttlMillis >= 0) {
            //设置过期时间
            builder.setExpiration(new Date(nowMillis + ttlMillis));
        }
        return builder.compact();
    }

    public static String parseToken(String token, String tokenSecret) {
        try {
            return Jwts.parser()
                    .setSigningKey(tokenSecret)
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (JwtException jwtException) {
            // 覆盖过期、签名不符、格式错误等所有 JWT 校验失败场景
            log.error("parseToken error", jwtException);
            throw new TicketFlowFrameException(BaseCode.TOKEN_EXPIRE);
        } catch (IllegalArgumentException e) {
            // token 为 null 或空串
            log.error("parseToken error", e);
            throw new TicketFlowFrameException(BaseCode.TOKEN_EXPIRE);
        }

    }

    public static void main(String[] args) {

        String tokenSecret = "***REMOVED***";

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("001key", "001value");
        jsonObject.put("002key", "001value");

        String token1 = TokenUtil.createToken("1", jsonObject.toJSONString(), 10000, tokenSecret);
        System.out.println("token:" + token1);


        String token2 = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxIiwiaWF0IjoxNjg4NTQyODM3LCJzdWIiOiJ7XCIwMDJrZXlcIjpcIjAwMXZhbHVlXCIsXCIwMDFrZXlcIjpcIjAwMXZhbHVlXCJ9IiwiZXhwIjoxNjg4NTQyODQ3fQ.vIKcAilTn_CR3VYssNE7rBpfuCSCH_RrkmsadLWf664";
        String subject = TokenUtil.parseToken(token2, tokenSecret);
        System.out.println("解析token后的值:" + subject);
    }
}