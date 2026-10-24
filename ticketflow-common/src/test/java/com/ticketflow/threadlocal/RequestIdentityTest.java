package com.ticketflow.threadlocal;

import com.ticketflow.constant.Constant;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RequestIdentity 身份解析测试：
 * 1. 只认网关注入的 user_id
 * 2. 缺失身份直接拒绝，不允许降级成"客户端说是谁就是谁"
 * 3. 非数字身份按请求头参数非法处理
 */
class RequestIdentityTest {

    @AfterEach
    void clearContext() {
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void requireUserIdReturnsGatewayInjectedIdentity() {
        BaseParameterHolder.setParameter(Constant.USER_ID, "10086");
        assertEquals(10086L, RequestIdentity.requireUserId());
    }

    @Test
    void requireUserIdRejectsMissingIdentity() {
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class, RequestIdentity::requireUserId);
        assertEquals(BaseCode.USER_NOT_LOGIN.getCode(), exception.getCode());
    }

    @Test
    void requireUserIdRejectsMalformedIdentity() {
        BaseParameterHolder.setParameter(Constant.USER_ID, "not-a-number");
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class, RequestIdentity::requireUserId);
        assertEquals(BaseCode.HEAD_ARGUMENT_EMPTY.getCode(), exception.getCode());
    }

    @Test
    void optionalUserIdIsNullWithoutIdentity() {
        assertNull(RequestIdentity.getUserId());
    }
}
