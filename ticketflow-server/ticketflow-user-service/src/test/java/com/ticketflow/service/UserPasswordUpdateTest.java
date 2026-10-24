package com.ticketflow.service;

import com.ticketflow.dto.UserUpdatePasswordDto;
import com.ticketflow.entity.User;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密链路测试：原密码错误必须被拒绝，原密码正确才允许写入新哈希。
 */
@ExtendWith(MockitoExtension.class)
class UserPasswordUpdateTest {

    @Mock
    private UserMapper userMapper;

    private UserService userService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final Long USER_ID = 10086L;
    private static final String CURRENT_PASSWORD = "current-password";

    @BeforeEach
    void setUp() {
        userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
    }

    private User currentUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setPassword(encoder.encode(CURRENT_PASSWORD));
        return user;
    }

    @Test
    void wrongOldPasswordIsRejected() {
        when(userMapper.selectById(USER_ID)).thenReturn(currentUser());
        UserUpdatePasswordDto dto = new UserUpdatePasswordDto();
        dto.setId(USER_ID);
        dto.setOldPassword("wrong-password");
        dto.setPassword("brand-new-password");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class, () -> userService.updatePassword(dto));
        assertEquals(BaseCode.NAME_PASSWORD_ERROR.getCode(), exception.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void correctOldPasswordWritesNewHash() {
        when(userMapper.selectById(USER_ID)).thenReturn(currentUser());
        UserUpdatePasswordDto dto = new UserUpdatePasswordDto();
        dto.setId(USER_ID);
        dto.setOldPassword(CURRENT_PASSWORD);
        dto.setPassword("brand-new-password");

        userService.updatePassword(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        String stored = captor.getValue().getPassword();
        assertNotEquals("brand-new-password", stored);
        assertTrue(encoder.matches("brand-new-password", stored));
    }
}
