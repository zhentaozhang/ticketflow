package com.ticketflow.service.composite.register.impl;

import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.tool.RequestCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegisterCountCheckHandlerTest {

    @Mock
    private RequestCounter requestCounter;

    private UserRegisterCountCheckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new UserRegisterCountCheckHandler();
        ReflectionTestUtils.setField(handler, "requestCounter", requestCounter);
    }

    @Test
    void executeShouldThrowWhenRequestCounterLimited() {
        when(requestCounter.onRequest()).thenReturn(true);

        TicketFlowFrameException exception = org.junit.jupiter.api.Assertions.assertThrows(
                TicketFlowFrameException.class, () -> handler.execute(new UserRegisterDto()));

        assertEquals(BaseCode.USER_REGISTER_FREQUENCY.getCode(), exception.getCode());
    }

    @Test
    void executeShouldPassWhenRequestCounterAllows() {
        when(requestCounter.onRequest()).thenReturn(false);

        assertDoesNotThrow(() -> handler.execute(new UserRegisterDto()));
    }
}
