package com.ticketflow.service;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.redis.RedisCache;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final String SECRET = "test-token-secret";

    @Mock
    private RedisCache redisCache;

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
    void getUserShouldReturnUserWhenPresentInRedis() {
        String token = TokenUtil.createToken("user-1", "{\"userId\":\"user-1\"}", 60000L, SECRET);
        UserVo expected = new UserVo();
        expected.setId("user-1");
        when(redisCache.get(any(), any())).thenReturn(expected);

        UserVo result = tokenService.getUser(token, "2", SECRET);

        assertEquals("user-1", result.getId());
    }

    @Test
    void getUserShouldThrowWhenMissingInRedis() {
        String token = TokenUtil.createToken("user-1", "{\"userId\":\"user-1\"}", 60000L, SECRET);
        when(redisCache.get(any(), any())).thenReturn(null);

        assertThrows(TicketFlowFrameException.class, () -> tokenService.getUser(token, "2", SECRET));
    }
}
