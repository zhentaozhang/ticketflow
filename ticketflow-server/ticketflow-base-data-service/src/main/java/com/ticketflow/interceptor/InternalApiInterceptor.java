package com.ticketflow.interceptor;

import com.ticketflow.annotation.InternalApi;
import com.ticketflow.constant.Constant;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.util.StringUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权拦截器。
 * <p>
 * 只处理被 {@link InternalApi} 标注的 Controller 方法/类：要求请求头
 * {@link Constant#INTERNAL_TOKEN} 与服务端配置的 {@code ticketflow.internal.token} 完全一致，
 * 否则抛 {@link BaseCode#INTERNAL_API_FORBIDDEN}。
 * <p>
 * 设计取舍：
 * 1) 未配置令牌时一律拒绝（fail closed）——宁可内部调用报错，也不能让"空令牌=放行"变成后门；
 * 2) 用 {@link MessageDigest#isEqual} 做定长时间比较，避免按字节比较泄露令牌前缀；
 * 3) 令牌只存在于服务端配置，不进前端 bundle，因此"能访问端口"不再等于"能拿到渠道私钥"。
 */
@Slf4j
public class InternalApiInterceptor implements HandlerInterceptor {

    private final String internalToken;

    public InternalApiInterceptor(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        boolean internalApi = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), InternalApi.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), InternalApi.class);
        if (!internalApi) {
            return true;
        }
        String provided = request.getHeader(Constant.INTERNAL_TOKEN);
        if (StringUtil.isEmpty(internalToken)) {
            log.error("内部接口拒绝访问：ticketflow.internal.token 未配置，uri={}", request.getRequestURI());
            throw new TicketFlowFrameException(BaseCode.INTERNAL_API_FORBIDDEN);
        }
        if (StringUtil.isEmpty(provided) || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("非法内部接口调用 uri={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            throw new TicketFlowFrameException(BaseCode.INTERNAL_API_FORBIDDEN);
        }
        return true;
    }
}
