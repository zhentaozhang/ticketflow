package com.ticketflow.service;

import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.BaseDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.UserAuthenticationDto;
import com.ticketflow.dto.UserLoginDto;
import com.ticketflow.dto.UserLogoutDto;
import com.ticketflow.dto.UserMobileDto;
import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.dto.UserUpdateDto;
import com.ticketflow.entity.User;
import com.ticketflow.entity.UserMobile;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.handler.BloomFilterHandler;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.jwt.TokenUtil;
import com.ticketflow.mapper.TicketUserMapper;
import com.ticketflow.mapper.UserEmailMapper;
import com.ticketflow.mapper.UserMapper;
import com.ticketflow.mapper.UserMobileMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.vo.GetChannelDataVo;
import com.ticketflow.vo.UserLoginVo;
import com.ticketflow.vo.UserVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String SECRET = "test-token-secret";

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserMobileMapper userMobileMapper;

    @Mock
    private UserEmailMapper userEmailMapper;

    @Mock
    private UidGenerator uidGenerator;

    @Mock
    private RedisCache redisCache;

    @Mock
    private TicketUserMapper ticketUserMapper;

    @Mock
    private BloomFilterHandler bloomFilterHandler;

    @Mock
    private CompositeContainer compositeContainer;

    @Mock
    private BaseDataClient baseDataClient;

    private UserService userService;

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

    @BeforeEach
    void setUp() {
        userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userMobileMapper", userMobileMapper);
        ReflectionTestUtils.setField(userService, "userEmailMapper", userEmailMapper);
        ReflectionTestUtils.setField(userService, "uidGenerator", uidGenerator);
        ReflectionTestUtils.setField(userService, "redisCache", redisCache);
        ReflectionTestUtils.setField(userService, "ticketUserMapper", ticketUserMapper);
        ReflectionTestUtils.setField(userService, "bloomFilterHandler", bloomFilterHandler);
        ReflectionTestUtils.setField(userService, "compositeContainer", compositeContainer);
        ReflectionTestUtils.setField(userService, "baseDataClient", baseDataClient);
        ReflectionTestUtils.setField(userService, "tokenExpireTime", 40L);
    }

    @Test
    void registerShouldInsertUserMobileAndAddToBloom() {
        when(uidGenerator.getUid()).thenReturn(100L, 101L);
        UserRegisterDto dto = new UserRegisterDto();
        dto.setMobile("13800000000");

        boolean result = userService.register(dto);

        assertTrue(result);
        verify(compositeContainer).execute(any(String.class), eq(dto));
        verify(userMapper).insert(any(User.class));
        verify(userMobileMapper).insert(any(UserMobile.class));
        verify(bloomFilterHandler).add("13800000000");
    }

    @Test
    void doExistShouldPassWhenBloomMiss() {
        when(bloomFilterHandler.contains("13800000000")).thenReturn(false);

        userService.doExist("13800000000");

        verify(userMobileMapper, never()).selectOne(any());
    }

    @Test
    void doExistShouldThrowWhenMobileRegistered() {
        when(bloomFilterHandler.contains("13800000000")).thenReturn(true);
        when(userMobileMapper.selectOne(any())).thenReturn(new UserMobile());

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.doExist("13800000000"));

        assertEquals(BaseCode.USER_EXIST.getCode(), exception.getCode());
    }

    @Test
    void doExistShouldPassWhenBloomHitButDbMiss() {
        when(bloomFilterHandler.contains("13800000000")).thenReturn(true);
        when(userMobileMapper.selectOne(any())).thenReturn(null);

        userService.doExist("13800000000");
    }

    @Test
    void loginShouldThrowWhenBothMobileAndEmailEmpty() {
        UserLoginDto dto = new UserLoginDto();

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.login(dto));

        assertEquals(BaseCode.USER_MOBILE_AND_EMAIL_NOT_EXIST.getCode(), exception.getCode());
    }

    @Test
    void loginShouldThrowWhenMobileErrorCountExceeded() {
        when(redisCache.get(any(), eq(String.class))).thenReturn("5");
        UserLoginDto dto = new UserLoginDto();
        dto.setCode("2");
        dto.setMobile("13800000000");
        dto.setPassword("pwd");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.login(dto));

        assertEquals(BaseCode.MOBILE_ERROR_COUNT_TOO_MANY.getCode(), exception.getCode());
    }

    @Test
    void loginShouldThrowAndRecordWhenMobileNotRegistered() {
        when(userMobileMapper.selectOne(any())).thenReturn(null);
        UserLoginDto dto = new UserLoginDto();
        dto.setCode("2");
        dto.setMobile("13800000000");
        dto.setPassword("pwd");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.login(dto));

        assertEquals(BaseCode.USER_MOBILE_EMPTY.getCode(), exception.getCode());
        verify(redisCache).incrBy(any(), eq(1L));
        verify(redisCache).expire(any(), eq(1L), eq(TimeUnit.MINUTES));
    }

    @Test
    void loginShouldThrowWhenPasswordMismatch() {
        UserMobile userMobile = new UserMobile();
        userMobile.setUserId(100L);
        when(userMobileMapper.selectOne(any())).thenReturn(userMobile);
        when(userMapper.selectOne(any())).thenReturn(null);
        UserLoginDto dto = new UserLoginDto();
        dto.setCode("2");
        dto.setMobile("13800000000");
        dto.setPassword("wrong");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.login(dto));

        assertEquals(BaseCode.NAME_PASSWORD_ERROR.getCode(), exception.getCode());
    }

    @Test
    void loginShouldSucceedAndPersistSessionAndReturnParsableToken() {
        UserMobile userMobile = new UserMobile();
        userMobile.setUserId(100L);
        User user = new User();
        user.setId(100L);
        user.setPassword("pwd");
        when(userMobileMapper.selectOne(any())).thenReturn(userMobile);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(uidGenerator.getUid()).thenReturn(999L);
        stubChannelDataFallback();

        UserLoginDto dto = new UserLoginDto();
        dto.setCode("2");
        dto.setMobile("13800000000");
        dto.setPassword("pwd");

        UserLoginVo result = userService.login(dto);

        assertEquals(100L, result.getUserId());
        verify(redisCache).set(any(), eq(user), eq(40L), eq(TimeUnit.MINUTES));
        String userId = JSONObject.parseObject(TokenUtil.parseToken(result.getToken(), SECRET)).getString("userId");
        assertEquals("100", userId);
    }

    @Test
    void loginWithEmailShouldThrowAndRecordWhenEmailNotRegistered() {
        when(userEmailMapper.selectOne(any())).thenReturn(null);
        UserLoginDto dto = new UserLoginDto();
        dto.setCode("2");
        dto.setEmail("someone@test.com");
        dto.setPassword("pwd");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.login(dto));

        assertEquals(BaseCode.USER_EMAIL_NOT_EXIST.getCode(), exception.getCode());
        verify(redisCache).incrBy(any(), eq(1L));
        verify(redisCache).expire(any(), eq(1L), eq(TimeUnit.MINUTES));
    }

    @Test
    void createTokenShouldBeParsableAndContainUserId() {
        when(uidGenerator.getUid()).thenReturn(999L);

        String token = userService.createToken(100L, SECRET);

        String subject = TokenUtil.parseToken(token, SECRET);
        assertEquals("100", JSONObject.parseObject(subject).getString("userId"));
    }

    @Test
    void logoutShouldDeleteRedisSession() {
        when(uidGenerator.getUid()).thenReturn(999L);
        stubChannelDataFallback();
        String token = userService.createToken(100L, SECRET);

        UserLogoutDto dto = new UserLogoutDto();
        dto.setCode("2");
        dto.setToken(token);

        boolean result = userService.logout(dto);

        assertTrue(result);
        verify(redisCache).del(any(com.ticketflow.redis.RedisKeyBuild.class));
    }

    @Test
    void getChannelDataByCodeShouldPreferRedisCache() {
        GetChannelDataVo channel = new GetChannelDataVo();
        channel.setCode("2");
        when(redisCache.get(any(), eq(GetChannelDataVo.class))).thenReturn(channel);

        GetChannelDataVo result = userService.getChannelDataByCode("2");

        assertEquals(channel, result);
        verifyNoInteractions(baseDataClient);
    }

    @Test
    void getChannelDataByCodeShouldFallbackToClientWhenCacheMiss() {
        stubChannelDataFallback();

        GetChannelDataVo result = userService.getChannelDataByCode("2");

        assertEquals("2", result.getCode());
        verify(baseDataClient).getByCode(any());
    }

    @Test
    void updateShouldThrowWhenUserNotExist() {
        when(userMapper.selectById(100L)).thenReturn(null);
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(100L);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.update(dto));

        assertEquals(BaseCode.USER_EMPTY.getCode(), exception.getCode());
    }

    @Test
    void getByMobileShouldReturnUserVo() {
        UserMobile userMobile = new UserMobile();
        userMobile.setUserId(100L);
        userMobile.setMobile("13800000000");
        when(userMobileMapper.selectOne(any())).thenReturn(userMobile);
        User user = new User();
        user.setId(100L);
        when(userMapper.selectById(100L)).thenReturn(user);

        UserMobileDto dto = new UserMobileDto();
        dto.setMobile("13800000000");

        UserVo result = userService.getByMobile(dto);

        assertEquals(100L, result.getId());
        assertEquals("138****0000", result.getMobile());
    }

    @Test
    void getByMobileShouldThrowWhenMobileNotRegistered() {
        when(userMobileMapper.selectOne(any())).thenReturn(null);
        UserMobileDto dto = new UserMobileDto();
        dto.setMobile("13800000000");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.getByMobile(dto));

        assertEquals(BaseCode.USER_MOBILE_EMPTY.getCode(), exception.getCode());
    }

    @Test
    void authenticationShouldThrowWhenAlreadyAuthenticated() {
        User user = new User();
        user.setRelAuthenticationStatus(1);
        when(userMapper.selectById(100L)).thenReturn(user);
        UserAuthenticationDto dto = new UserAuthenticationDto();
        dto.setId(100L);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> userService.authentication(dto));

        assertEquals(BaseCode.USER_AUTHENTICATION.getCode(), exception.getCode());
    }

    private void stubChannelDataFallback() {
        GetChannelDataVo channel = new GetChannelDataVo();
        channel.setCode("2");
        channel.setTokenSecret(SECRET);
        when(baseDataClient.getByCode(any())).thenReturn(ApiResponse.ok(channel));
    }
}
