package com.ticketflow.service.composite.register.impl;

import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.VerifyCaptcha;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.service.CaptchaHandle;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegisterVerifyCaptchaTest {

    @Mock
    private CaptchaHandle captchaHandle;

    @Mock
    private RedisCache redisCache;

    private UserRegisterVerifyCaptcha handler;

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
        handler = new UserRegisterVerifyCaptcha();
        ReflectionTestUtils.setField(handler, "captchaHandle", captchaHandle);
        ReflectionTestUtils.setField(handler, "redisCache", redisCache);
    }

    @Test
    void executeShouldThrowWhenPasswordsDiffer() {
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("654321");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> handler.execute(dto));

        assertEquals(BaseCode.TWO_PASSWORDS_DIFFERENT.getCode(), exception.getCode());
    }

    @Test
    void executeShouldThrowWhenCaptchaIdNotConfigured() {
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        dto.setCaptchaId("captcha-1");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> handler.execute(dto));

        assertEquals(BaseCode.VERIFY_CAPTCHA_ID_NOT_EXIST.getCode(), exception.getCode());
    }

    @Test
    void executeShouldThrowWhenCaptchaRequiredButVerificationEmpty() {
        when(redisCache.get(any(), eq(String.class))).thenReturn(VerifyCaptcha.YES.getValue());
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        dto.setCaptchaId("captcha-1");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> handler.execute(dto));

        assertEquals(BaseCode.VERIFY_CAPTCHA_EMPTY.getCode(), exception.getCode());
        verify(captchaHandle, never()).verification(any());
    }

    @Test
    void executeShouldVerifyCaptchaWhenVerificationProvided() {
        when(redisCache.get(any(), eq(String.class))).thenReturn(VerifyCaptcha.YES.getValue());
        when(captchaHandle.verification(any())).thenReturn(ResponseModel.success());
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        dto.setCaptchaId("captcha-1");
        dto.setCaptchaVerification("verification-code");

        assertDoesNotThrow(() -> handler.execute(dto));

        verify(captchaHandle).verification(any(CaptchaVO.class));
    }

    @Test
    void executeShouldThrowWhenCaptchaVerificationFails() {
        when(redisCache.get(any(), eq(String.class))).thenReturn(VerifyCaptcha.YES.getValue());
        ResponseModel failure = new ResponseModel();
        failure.setRepCode("1001");
        failure.setRepMsg("验证失败");
        when(captchaHandle.verification(any())).thenReturn(failure);
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        dto.setCaptchaId("captcha-1");
        dto.setCaptchaVerification("wrong-code");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> handler.execute(dto));

        assertEquals("1001", exception.getCode().toString());
    }

    @Test
    void executeShouldSkipCaptchaWhenNotRequired() {
        when(redisCache.get(any(), eq(String.class))).thenReturn(VerifyCaptcha.NO.getValue());
        UserRegisterDto dto = new UserRegisterDto();
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        dto.setCaptchaId("captcha-1");

        assertDoesNotThrow(() -> handler.execute(dto));

        verify(captchaHandle, never()).verification(any());
    }
}
