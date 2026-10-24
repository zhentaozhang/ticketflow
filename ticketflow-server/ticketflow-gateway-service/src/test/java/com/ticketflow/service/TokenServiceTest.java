package com.ticketflow.service;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.redis.session.UserSessionStore;
import com.ticketflow.vo.UserVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String SECRET = "test-token-secret";

    @Mock
    private UserSessionStore userSessionStore;

    @InjectMocks
    private TokenService tokenService;

    @BeforeAll
    static void initSpringUtil() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(mock(ConfigurableEnvironment.class));
        new SpringUtil().initialize(context);
    }

    @AfterAll
    static void clearSpringUtil() {
        new SpringUtil().initialize(null);
    }

    @Test
    void parseTokenShouldExtractUserIdFromSubject() {
        String token = TokenUtil.createToken("user-1", "{\"userId\":\"user-1\"}", 60000L, SECRET);

        assertEquals("user-1", tokenService.parseToken(token, SECRET));
    }

    @Test
    void parseTokenShouldReturnNullWhenSubjectHasNoUserId() {
        String token = TokenUtil.createToken("user-1", "{\"name\":\"someone\"}", 60000L, SECRET);

        assertNull(tokenService.parseToken(token, SECRET));
    }

    @Test
    void parseTokenShouldRejectInvalidToken() {
        assertThrows(TicketFlowFrameException.class, () -> tokenService.parseToken("not-a-token", SECRET));
    }

    @Test
    void getUserShouldReturnUserWhenSessionSignatureValid() {
        String token = TokenUtil.createToken("10086", "{\"userId\":\"10086\"}", 60000L, SECRET);
        UserVo expected = new UserVo();
        expected.setId("10086");
        // 会话经 UserSessionStore 验签通过后才会返回负载
        when(userSessionStore.loadPayload("2", 10086L, UserVo.class)).thenReturn(expected);

        UserVo result = tokenService.getUser(token, "2", SECRET);

        assertEquals("10086", result.getId());
    }

    @Test
    void getUserShouldThrowWhenSessionMissingOrForged() {
        String token = TokenUtil.createToken("10086", "{\"userId\":\"10086\"}", 60000L, SECRET);

        // 默认 mock 返回 null：等价于 Redis 无该会话，或会话签名校验失败
        assertThrows(TicketFlowFrameException.class, () -> tokenService.getUser(token, "2", SECRET));
    }
}
