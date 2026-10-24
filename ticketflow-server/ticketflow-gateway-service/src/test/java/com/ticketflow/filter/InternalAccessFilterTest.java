package com.ticketflow.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static com.ticketflow.constant.Constant.INTERNAL_TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalAccessFilter 用例：外部请求不得触达内部接口，且转发给下游的请求必须带服务端令牌。
 */
class InternalAccessFilterTest {

    private static final String TOKEN = "unit-test-internal-token";

    @Test
    void blockInternalPathsWith404() {
        InternalAccessFilter filter = new InternalAccessFilter(TOKEN);
        String[] blocked = {
                "/ticketflow/program/program/interior/reduce/remain/number",
                "/ticketflow/program/program/record/task/update",
                "/ticketflow/basedata/channel/data/getByCode",
                "/ticketflow/customize/message/record/select",
                "/actuator/env",
                "/actuator/heapdump"
        };
        for (String path : blocked) {
            AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());

            filter.filter(exchange, capture(passed)).block();

            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode(), path);
            assertNull(passed.get(), path + " 不得进入路由链");
        }
    }

    @Test
    void allowBusinessPathAndOverrideForgedToken() {
        InternalAccessFilter filter = new InternalAccessFilter(TOKEN);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/ticketflow/user/user/update")
                .header(INTERNAL_TOKEN, "forged")
                .build());

        filter.filter(exchange, capture(passed)).block();

        assertTrue(passed.get() != null, "普通业务路径必须继续路由");
        assertEquals(TOKEN, passed.get().getRequest().getHeaders().getFirst(INTERNAL_TOKEN),
                "客户端伪造的令牌必须被服务端令牌覆盖");
    }

    @Test
    void allowActuatorHealthAndPrometheus() {
        InternalAccessFilter filter = new InternalAccessFilter(TOKEN);
        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus"}) {
            AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());

            filter.filter(exchange, capture(passed)).block();

            assertTrue(passed.get() != null, path + " 必须放行以便探活/抓取");
        }
    }

    @Test
    void dropForgedTokenWhenServerTokenBlank() {
        InternalAccessFilter filter = new InternalAccessFilter("");
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/ticketflow/user/user/update")
                .header(INTERNAL_TOKEN, "forged")
                .build());

        filter.filter(exchange, capture(passed)).block();

        assertTrue(passed.get() != null);
        assertNull(passed.get().getRequest().getHeaders().getFirst(INTERNAL_TOKEN),
                "服务端未配置令牌时不得把伪造值透传下去");
    }

    @Test
    void toDownstreamPathStripsServicePrefix() {
        assertEquals("/program/interior/x",
                InternalAccessFilter.toDownstreamPath("/ticketflow/program/program/interior/x"));
        assertEquals("/user/update",
                InternalAccessFilter.toDownstreamPath("/ticketflow/user/user/update"));
        assertEquals("/program/interior/x",
                InternalAccessFilter.toDownstreamPath("/program/interior/x"));
        assertFalse(InternalAccessFilter.toDownstreamPath("/ticketflow/program/program/interior/x")
                .startsWith("/program/program"));
    }

    private WebFilterChain capture(AtomicReference<ServerWebExchange> passed) {
        return exchange -> {
            passed.set(exchange);
            return Mono.empty();
        };
    }
}
