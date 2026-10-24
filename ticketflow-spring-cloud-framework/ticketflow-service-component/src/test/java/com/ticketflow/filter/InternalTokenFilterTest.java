package com.ticketflow.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.ticketflow.constant.Constant.INTERNAL_TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalTokenFilter 用例：验证「网关之外无入口」的核心约束——
 * 除探活/指标/文档端点外，任何请求都必须带服务端令牌，且配置为空时 fail closed。
 */
class InternalTokenFilterTest {

    private static final String TOKEN = "unit-test-internal-token";

    @Test
    void allowWhenTokenMatches() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = request("/program/interior/reduce/remain/number");
        request.addHeader(INTERNAL_TOKEN, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "带正确令牌的请求必须继续执行");
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectWhenTokenMissing() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/program/record/task/update"), response, chain);

        assertNull(chain.getRequest(), "缺少内部令牌的请求不得进入业务链路");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("520"));
    }

    @Test
    void rejectWhenTokenWrong() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = request("/user/user/update/mobile");
        request.addHeader(INTERNAL_TOKEN, "forged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
    }

    @Test
    void failClosedWhenConfiguredTokenEmpty() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("");
        MockHttpServletRequest request = request("/program/program/detail");
        // 即便调用方不带头，也必须拒绝：空令牌放行等于后门
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
    }

    @Test
    void allowProbeAndMetricsWithoutToken() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        for (String uri : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus",
                "/v3/api-docs/swagger-config", "/doc.html", "/error"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(uri), response, chain);

            assertNotNull(chain.getRequest(), uri + " 属于探活/指标/文档白名单，应放行");
            assertEquals(200, response.getStatus(), uri);
        }
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
