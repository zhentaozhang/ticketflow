package com.ticketflow.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话签名测试：签名必须覆盖 userId/code/载荷，任一被改动即失效；密钥缺失时 fail-closed。
 */
class SessionSignerTest {

    private static final String SECRET = "unit-test-secret";
    private static final String USER_ID = "10086";
    private static final String CODE = "ticketflow";
    private static final long ISSUED_AT = 1_700_000_000_000L;
    private static final String PAYLOAD = "{\"id\":10086,\"name\":\"tester\"}";

    @Test
    void sameInputProducesVerifiableSign() {
        String sign = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        assertTrue(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD, sign));
        // 大小写不敏感（签名统一输出小写，兼容历史大写）
        assertTrue(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD, sign.toUpperCase()));
    }

    @Test
    void signIsDeterministic() {
        String first = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        String second = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        assertEquals(first, second);
    }

    @Test
    void payloadOrIdentityTamperingFailsVerification() {
        String sign = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        // 改载荷（把登录用户换成别人）
        assertFalse(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT, "{\"id\":999}", sign));
        // 改 userId
        assertFalse(SessionSigner.verify(SECRET, "999", CODE, ISSUED_AT, PAYLOAD, sign));
        // 改 code（跨渠道复用）
        assertFalse(SessionSigner.verify(SECRET, USER_ID, "other", ISSUED_AT, PAYLOAD, sign));
        // 改签发时间
        assertFalse(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT + 1, PAYLOAD, sign));
    }

    @Test
    void differentSecretFailsVerification() {
        String sign = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        assertFalse(SessionSigner.verify("another-secret", USER_ID, CODE, ISSUED_AT, PAYLOAD, sign));
    }

    @Test
    void missingSecretFailsClosed() {
        assertThrows(IllegalStateException.class, () -> SessionSigner.sign("  ", USER_ID, CODE, ISSUED_AT, PAYLOAD));
        String sign = SessionSigner.sign(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD);
        assertFalse(SessionSigner.verify("", USER_ID, CODE, ISSUED_AT, PAYLOAD, sign));
    }

    @Test
    void missingSignFailsVerification() {
        assertFalse(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD, null));
        assertFalse(SessionSigner.verify(SECRET, USER_ID, CODE, ISSUED_AT, PAYLOAD, ""));
    }
}
