package com.ticketflow.session;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 会话签名工具（HMAC-SHA256）。
 * <p>
 * 背景：网关判断"是否登录"过去只依赖 Redis key 是否存在（{@code EXISTS user_login_{code}_{userId}}）。
 * Redis 一旦可被外部写入（无密码/无 ACL），攻击者可以直接 SET 该 key 伪造任意用户会话。
 * 因此会话值现在由 {@link UserSessionVo} 承载，并对
 * {@code userId|code|issuedAt|payload} 做 HMAC 签名，网关校验签名通过才认这条会话。
 * <p>
 * 签名密钥只存在于网关与 user-service 的配置里（{@code ticketflow.session.sign-secret}），
 * 不落 Redis，所以仅有 Redis 写权限不足以伪造合法会话。
 */
public final class SessionSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private SessionSigner() {
    }

    /**
     * 生成会话签名。
     *
     * @param secret   签名密钥，空白视为配置缺失，直接抛异常（不做 fail-open）
     * @param userId   会话归属用户 id
     * @param code     渠道 code（会话 key 的组成部分，防止跨渠道复用会话）
     * @param issuedAt 签发时间戳
     * @param payload  会话负载（用户快照 JSON）
     * @return 十六进制小写签名
     */
    public static String sign(String secret, String userId, String code, long issuedAt, String payload) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("会话签名密钥 ticketflow.session.sign-secret 未配置，拒绝签发会话");
        }
        return hmacHex(secret, canonical(userId, code, issuedAt, payload));
    }

    /**
     * 校验会话签名。任何异常（密钥缺失、签名缺失）都返回 false，即 fail-closed。
     */
    public static boolean verify(String secret, String userId, String code, long issuedAt, String payload, String sign) {
        if (secret == null || secret.trim().isEmpty()
                || sign == null || sign.trim().isEmpty()
                || userId == null || payload == null) {
            return false;
        }
        String expected = hmacHex(secret, canonical(userId, code, issuedAt, payload));
        // 定长时间比较，避免通过响应时间侧信道逐字节猜测签名
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(String userId, String code, long issuedAt, String payload) {
        return userId + "|" + (code == null ? "" : code) + "|" + issuedAt + "|" + payload;
    }

    private static String hmacHex(String secret, String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("会话签名计算失败", e);
        }
    }
}
