package com.ticketflow.session;

import lombok.Data;

/**
 * Redis 会话存储结构（user_login_{code}_{userId} 的值）。
 * <p>
 * 为什么要包一层而不是直接存用户快照：需要在值上附加 HMAC 签名，
 * 让"key 存在"不再等价于"会话合法"，仅持有 Redis 写权限无法伪造登录态。
 */
@Data
public class UserSessionVo {

    /** 会话归属用户 id（与 key 中的 userId 必须一致，参与签名）。 */
    private String userId;

    /** 渠道 code（与 key 中的 code 必须一致，参与签名，防止会话跨渠道复用）。 */
    private String code;

    /** 签发时间戳（毫秒），参与签名，防止旧签名被复用到新负载。 */
    private long issuedAt;

    /** 用户快照 JSON（user-service 写入，网关解析为 UserVo）。 */
    private String payload;

    /** HMAC-SHA256 签名。 */
    private String sign;
}
