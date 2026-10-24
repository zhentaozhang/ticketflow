package com.ticketflow.redis.session;

import com.alibaba.fastjson.JSON;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.session.SessionSigner;
import com.ticketflow.session.UserSessionVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 会话存储测试：写出的信封必须带可校验签名，读取时任何伪造/搬移/篡改都必须被判为未登录。
 */
class UserSessionStoreTest {

    private static final String SECRET = "unit-test-secret";
    private static final String CODE = "ticketflow";
    private static final Long USER_ID = 10086L;

    private RedisCache redisCache;

    private UserSessionStore sessionStore;

    @BeforeEach
    void setUp() {
        redisCache = mock(RedisCache.class);
        sessionStore = new UserSessionStore(redisCache, SECRET);
    }

    private String writtenEnvelopeJson() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redisCache).set(any(RedisKeyBuild.class), captor.capture(), eq(30L), eq(TimeUnit.MINUTES));
        UserSessionVo written = (UserSessionVo) captor.getValue();
        assertNotNull(written.getSign(), "写入的会话必须带签名");
        return JSON.toJSONString(written);
    }

    private void stubRead(String json) {
        doReturn(json).when(redisCache).get(any(RedisKeyBuild.class), eq(String.class));
    }

    @Test
    void saveThenLoadReturnsPayload() {
        UserVoFixture fixture = new UserVoFixture();
        fixture.id = USER_ID;
        fixture.name = "tester";
        sessionStore.save(CODE, USER_ID, fixture, 30L, TimeUnit.MINUTES);

        stubRead(writtenEnvelopeJson());
        UserVoFixture loaded = sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class);
        assertNotNull(loaded);
        assertEquals(USER_ID, loaded.id);
        assertEquals("tester", loaded.name);
        assertNull(loaded.password, "会话负载不应包含密码");
    }

    @Test
    void missingSessionReturnsNull() {
        stubRead(null);
        assertNull(sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class));
    }

    @Test
    void forgedEnvelopeWithoutSignatureReturnsNull() {
        // 模拟攻击者只拿到 Redis 写权限，自己 SET 一个没有合法签名的会话值
        UserSessionVo forged = new UserSessionVo();
        forged.setUserId(String.valueOf(USER_ID));
        forged.setCode(CODE);
        forged.setIssuedAt(System.currentTimeMillis());
        forged.setPayload("{\"id\":10086,\"name\":\"attacker\"}");
        forged.setSign(SessionSigner.sign("attacker-guessed-secret", String.valueOf(USER_ID), CODE,
                forged.getIssuedAt(), forged.getPayload()));
        stubRead(JSON.toJSONString(forged));
        assertNull(sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class));
    }

    @Test
    void tamperedPayloadReturnsNull() {
        UserVoFixture fixture = new UserVoFixture();
        fixture.id = USER_ID;
        sessionStore.save(CODE, USER_ID, fixture, 30L, TimeUnit.MINUTES);
        String json = writtenEnvelopeJson();
        UserSessionVo envelope = JSON.parseObject(json, UserSessionVo.class);
        // 保留原签名，但把负载里的用户换成别人
        String tamperedPayload = envelope.getPayload().replace(String.valueOf(USER_ID), "999");
        envelope.setPayload(tamperedPayload);
        stubRead(JSON.toJSONString(envelope));
        assertNull(sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class));
    }

    @Test
    void sessionFromAnotherKeyReturnsNull() {
        UserVoFixture fixture = new UserVoFixture();
        fixture.id = 999L;
        // 用 B 用户/渠道写出的合法会话，搬到 A 用户/另一渠道的 key 上
        sessionStore.save(CODE, 999L, fixture, 30L, TimeUnit.MINUTES);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redisCache).set(any(RedisKeyBuild.class), captor.capture(), eq(30L), eq(TimeUnit.MINUTES));
        stubRead(JSON.toJSONString(captor.getValue()));

        assertNull(sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class));
        assertNull(sessionStore.loadPayload("another-code", 999L, UserVoFixture.class));
    }

    @Test
    void malformedJsonReturnsNull() {
        stubRead("not-a-json");
        assertNull(sessionStore.loadPayload(CODE, USER_ID, UserVoFixture.class));
    }

    @Test
    void invalidateDeletesSessionKey() {
        sessionStore.invalidate(CODE, USER_ID);
        verify(redisCache).del(any(RedisKeyBuild.class));
        // 缺 code 时不做任何删除，避免误删其他渠道会话
        sessionStore.invalidate(null, USER_ID);
    }

    /**
     * 会话负载样例（模拟 user-service 写入的脱敏用户快照）。
     */
    static class UserVoFixture {
        public Long id;
        public String name;
        public String password;
    }
}
