package com.ticketflow.service;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.redis.session.UserSessionStore;
import com.ticketflow.vo.UserVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 网关会话校验测试：JWT 合法但 Redis 会话缺失/被伪造（loadPayload 返回 null）必须判为未登录。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceSessionTest {

    private static final String TOKEN_SECRET = "unit-test-token-secret";
    private static final String CODE = "ticketflow";
    private static final long USER_ID = 10086L;

    @Mock
    private UserSessionStore userSessionStore;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "userSessionStore", userSessionStore);
    }

    private String tokenOf(String userId) {
        return TokenUtil.createToken("12345", "{\"userId\":\"" + userId + "\"}", 600_000L, TOKEN_SECRET);
    }

    @Test
    void signedSessionIsAccepted() {
        UserVo userVo = new UserVo();
        userVo.setId(String.valueOf(USER_ID));
        userVo.setName("tester");
        when(userSessionStore.loadPayload(CODE, USER_ID, UserVo.class)).thenReturn(userVo);

        UserVo result = tokenService.getUser(tokenOf(String.valueOf(USER_ID)), CODE, TOKEN_SECRET);
        assertEquals(String.valueOf(USER_ID), result.getId());
    }

    @Test
    void missingOrForgedSessionIsRejected() {
        // Redis 里没有会话，或会话签名校验失败 → loadPayload 返回 null
        when(userSessionStore.loadPayload(CODE, USER_ID, UserVo.class)).thenReturn(null);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> tokenService.getUser(tokenOf(String.valueOf(USER_ID)), CODE, TOKEN_SECRET));
        assertEquals(BaseCode.LOGIN_USER_NOT_EXIST.getCode(), exception.getCode());
    }

    @Test
    void nonNumericUserIdInTokenIsRejected() {
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> tokenService.getUser(tokenOf("not-a-number"), CODE, TOKEN_SECRET));
        assertEquals(BaseCode.LOGIN_USER_NOT_EXIST.getCode(), exception.getCode());
        // 非数字 userId 在解析阶段就被拒绝，不应打到 Redis
        verifyNoInteractions(userSessionStore);
    }
}
