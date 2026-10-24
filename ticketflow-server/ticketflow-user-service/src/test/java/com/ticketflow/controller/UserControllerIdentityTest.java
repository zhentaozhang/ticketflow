package com.ticketflow.controller;

import com.ticketflow.constant.Constant;
import com.ticketflow.dto.UserUpdateDto;
import com.ticketflow.dto.UserUpdateEmailDto;
import com.ticketflow.dto.UserUpdateMobileDto;
import com.ticketflow.dto.UserUpdatePasswordDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.UserService;
import com.ticketflow.threadlocal.BaseParameterHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 用户写接口身份收敛测试：改资料/改密/改邮箱/改手机号只能作用于登录用户，
 * 请求体里的 id 一律被网关注入的身份覆盖。
 */
class UserControllerIdentityTest {

    private UserController controller;
    private UserService userService;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        userService = mock(UserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        BaseParameterHolder.setParameter(Constant.USER_ID, "10086");
    }

    @AfterEach
    void tearDown() {
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void updateBindsLoginUser() {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(9999L);
        dto.setName("attacker");
        controller.update(dto);

        ArgumentCaptor<UserUpdateDto> captor = ArgumentCaptor.forClass(UserUpdateDto.class);
        verify(userService).update(captor.capture());
        assertEquals(10086L, captor.getValue().getId());
    }

    @Test
    void updatePasswordBindsLoginUser() {
        UserUpdatePasswordDto dto = new UserUpdatePasswordDto();
        dto.setId(9999L);
        dto.setOldPassword("old-password");
        dto.setPassword("new-password");
        controller.updatePassword(dto);

        ArgumentCaptor<UserUpdatePasswordDto> captor = ArgumentCaptor.forClass(UserUpdatePasswordDto.class);
        verify(userService).updatePassword(captor.capture());
        assertEquals(10086L, captor.getValue().getId());
    }

    @Test
    void updateEmailBindsLoginUser() {
        UserUpdateEmailDto dto = new UserUpdateEmailDto();
        dto.setId(9999L);
        dto.setEmail("attacker@example.com");
        controller.updateEmail(dto);

        ArgumentCaptor<UserUpdateEmailDto> captor = ArgumentCaptor.forClass(UserUpdateEmailDto.class);
        verify(userService).updateEmail(captor.capture());
        assertEquals(10086L, captor.getValue().getId());
    }

    @Test
    void updateMobileBindsLoginUser() {
        UserUpdateMobileDto dto = new UserUpdateMobileDto();
        dto.setId(9999L);
        dto.setMobile("13800000000");
        controller.updateMobile(dto);

        ArgumentCaptor<UserUpdateMobileDto> captor = ArgumentCaptor.forClass(UserUpdateMobileDto.class);
        verify(userService).updateMobile(captor.capture());
        assertEquals(10086L, captor.getValue().getId());
    }

    @Test
    void missingLoginIdentityIsRejected() {
        BaseParameterHolder.removeParameterMap();
        UserUpdateEmailDto dto = new UserUpdateEmailDto();
        dto.setEmail("attacker@example.com");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class, () -> controller.updateEmail(dto));
        assertEquals(BaseCode.USER_NOT_LOGIN.getCode(), exception.getCode());
    }
}
