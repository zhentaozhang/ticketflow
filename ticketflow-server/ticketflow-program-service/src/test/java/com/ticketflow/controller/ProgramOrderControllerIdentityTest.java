package com.ticketflow.controller;

import com.ticketflow.constant.Constant;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.strategy.ProgramOrderContext;
import com.ticketflow.service.strategy.ProgramOrderStrategy;
import com.ticketflow.threadlocal.BaseParameterHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单入口身份收敛测试：
 * 请求体里的 userId 一律被网关注入的登录身份覆盖，
 * 缺失登录身份时直接拒绝，避免"改个 userId 就能用别人的账号下单/占限购"。
 */
class ProgramOrderControllerIdentityTest {

    private ProgramOrderController controller;
    private ProgramOrderContext programOrderContext;
    private ProgramOrderStrategy strategy;

    @BeforeEach
    void setUp() {
        controller = new ProgramOrderController();
        programOrderContext = mock(ProgramOrderContext.class);
        strategy = mock(ProgramOrderStrategy.class);
        ReflectionTestUtils.setField(controller, "programOrderContext", programOrderContext);
        when(programOrderContext.get(any())).thenReturn(strategy);
        when(strategy.createOrder(any())).thenReturn("order-no");
        BaseParameterHolder.setParameter(Constant.USER_ID, "10086");
    }

    @AfterEach
    void tearDown() {
        BaseParameterHolder.removeParameterMap();
    }

    private ProgramOrderCreateDto bodyWithUserId(Long bodyUserId) {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setProgramId(1L);
        dto.setUserId(bodyUserId);
        dto.setTicketUserIdList(List.of(2L));
        dto.setTicketCategoryId(3L);
        dto.setTicketCount(1);
        return dto;
    }

    @Test
    void bodyUserIdIsOverwrittenByGatewayIdentity() {
        ProgramOrderCreateDto dto = bodyWithUserId(999999L);
        controller.createV4(dto);

        ArgumentCaptor<ProgramOrderCreateDto> captor = ArgumentCaptor.forClass(ProgramOrderCreateDto.class);
        verify(strategy).createOrder(captor.capture());
        assertEquals(10086L, captor.getValue().getUserId());
    }

    @Test
    void allVersionsBindIdentity() {
        for (String version : List.of(ProgramOrderVersion.V1_VERSION.getVersion(),
                ProgramOrderVersion.V2_VERSION.getVersion(),
                ProgramOrderVersion.V3_VERSION.getVersion(),
                ProgramOrderVersion.V4_VERSION.getVersion())) {
            ProgramOrderContext context = mock(ProgramOrderContext.class);
            ProgramOrderStrategy strategyMock = mock(ProgramOrderStrategy.class);
            when(context.get(version)).thenReturn(strategyMock);
            when(strategyMock.createOrder(any())).thenReturn("no");
            ProgramOrderController c = new ProgramOrderController();
            ReflectionTestUtils.setField(c, "programOrderContext", context);

            ProgramOrderCreateDto dto = bodyWithUserId(1L);
            switch (version) {
                case "v1" -> c.createV1(dto);
                case "v2" -> c.createV2(dto);
                case "v3" -> c.createV3(dto);
                default -> c.createV4(dto);
            }

            ArgumentCaptor<ProgramOrderCreateDto> captor = ArgumentCaptor.forClass(ProgramOrderCreateDto.class);
            verify(strategyMock).createOrder(captor.capture());
            assertEquals(10086L, captor.getValue().getUserId(), "version=" + version);
        }
    }

    @Test
    void missingLoginIdentityIsRejected() {
        BaseParameterHolder.removeParameterMap();
        ProgramOrderCreateDto dto = bodyWithUserId(999999L);

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class, () -> controller.createV1(dto));
        assertEquals(BaseCode.USER_NOT_LOGIN.getCode(), exception.getCode());
    }
}
