package com.ticketflow.service.composite.register.impl;

import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserExistCheckHandlerTest {

    @Mock
    private UserService userService;

    private UserExistCheckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new UserExistCheckHandler();
        ReflectionTestUtils.setField(handler, "userService", userService);
    }

    @Test
    void executeShouldDelegateToUserServiceDoExist() {
        UserRegisterDto dto = new UserRegisterDto();
        dto.setMobile("13800000000");

        handler.execute(dto);

        verify(userService).doExist("13800000000");
    }

    @Test
    void shouldBePartOfRegisterCheckTree() {
        assertEquals(CompositeCheckType.USER_REGISTER_CHECK.getValue(), handler.type());
        assertEquals(2, handler.executeOrder());
        assertEquals(2, handler.executeTier());
        assertEquals(1, handler.executeParentOrder());
    }
}
