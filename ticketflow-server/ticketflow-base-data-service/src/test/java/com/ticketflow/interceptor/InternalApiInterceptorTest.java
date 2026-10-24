package com.ticketflow.interceptor;

import com.ticketflow.annotation.InternalApi;
import com.ticketflow.constant.Constant;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部接口鉴权拦截器测试。
 * <p>
 * 覆盖：带对令牌放行、缺令牌/错令牌拒绝、未配置令牌时 fail closed、非 @InternalApi 接口不受影响。
 */
class InternalApiInterceptorTest {

    private static final String TOKEN = "s3cr3t-internal-token";

    @Test
    void shouldPassWhenTokenMatches() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/channel/data/getSecretByCode");
        request.addHeader(Constant.INTERNAL_TOKEN, TOKEN);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler()));
    }

    @Test
    void shouldRejectWhenTokenMissing() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/channel/data/getSecretByCode");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler()));
        assertEquals(BaseCode.INTERNAL_API_FORBIDDEN.getCode(), exception.getCode());
    }

    @Test
    void shouldRejectWhenTokenWrong() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/channel/data/getSecretByCode");
        request.addHeader(Constant.INTERNAL_TOKEN, "wrong-token");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler()));
        assertEquals(BaseCode.INTERNAL_API_FORBIDDEN.getCode(), exception.getCode());
    }

    @Test
    void shouldFailClosedWhenServerTokenMissing() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/channel/data/getSecretByCode");
        // 即使客户端把空令牌当"空头"发过来，也不能放行
        request.addHeader(Constant.INTERNAL_TOKEN, "");

        assertThrows(TicketFlowFrameException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler()));
    }

    @Test
    void shouldIgnoreNonInternalApiHandler() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/channel/data/getByCode");
        HandlerMethod publicHandler = new HandlerMethod(new PublicEndpoint(), PublicEndpoint.class.getMethod("getByCode"));

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), publicHandler));
    }

    @Test
    void shouldIgnoreNonHandlerMethod() throws Exception {
        InternalApiInterceptor interceptor = new InternalApiInterceptor(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new InternalEndpoint(), InternalEndpoint.class.getMethod("getSecretByCode"));
    }

    /** 模拟被 @InternalApi 保护的内部接口 */
    static class InternalEndpoint {
        @InternalApi
        public void getSecretByCode() {
        }
    }

    /** 模拟公开接口 */
    static class PublicEndpoint {
        public void getByCode() {
        }
    }
}
