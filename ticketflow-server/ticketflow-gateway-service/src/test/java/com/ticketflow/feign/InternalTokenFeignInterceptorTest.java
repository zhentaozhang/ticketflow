package com.ticketflow.feign;

import com.ticketflow.constant.Constant;
import feign.RequestTemplate;
import feign.Target;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部令牌透传拦截器测试。
 * <p>
 * 关键约束：令牌只能出现在调用 base-data-service 的请求上；
 * 未配置令牌时不附加（让目标服务 fail closed 拒绝，而不是静默放行）。
 * <p>
 * 注意：feign.RequestTemplate 是 final 类，不能被 Mockito 代理，
 * 所以这里用真实实例 + 公开 setter（feignTarget(Target)）构造目标。
 */
class InternalTokenFeignInterceptorTest {

    private static final String TOKEN = "s3cr3t-internal-token";

    @Test
    void shouldAttachTokenWhenCallingBaseDataService() {
        RequestTemplate template = templateFor("ticketflow-base-data-service");

        new InternalTokenFeignInterceptor(TOKEN).apply(template);

        assertEquals(TOKEN, singleHeader(template, Constant.INTERNAL_TOKEN));
    }

    @Test
    void shouldNotAttachTokenWhenCallingOtherService() {
        RequestTemplate template = templateFor("ticketflow-order-service");

        new InternalTokenFeignInterceptor(TOKEN).apply(template);

        // 令牌不扩散：order/pay/customize 等无关服务不应看到内部令牌
        assertFalse(template.headers().containsKey(Constant.INTERNAL_TOKEN));
    }

    @Test
    void shouldNotAttachTokenWhenTargetIsNull() {
        RequestTemplate template = new RequestTemplate();

        new InternalTokenFeignInterceptor(TOKEN).apply(template);

        assertFalse(template.headers().containsKey(Constant.INTERNAL_TOKEN));
    }

    @Test
    void shouldNotAttachTokenWhenTokenNotConfigured() {
        RequestTemplate template = templateFor("ticketflow-base-data-service");

        new InternalTokenFeignInterceptor("").apply(template);

        assertFalse(template.headers().containsKey(Constant.INTERNAL_TOKEN));
    }

    private static RequestTemplate templateFor(String serviceName) {
        Target<Object> target = mock(Target.class);
        when(target.name()).thenReturn(serviceName);
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(target);
        return template;
    }

    private static String singleHeader(RequestTemplate template, String name) {
        Map<String, Collection<String>> headers = template.headers();
        assertTrue(headers.containsKey(name), "缺少请求头 " + name);
        Collection<String> values = headers.get(name);
        assertEquals(1, values.size());
        return values.iterator().next();
    }
}
