package com.ticketflow.feign;

import com.ticketflow.util.StringUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

import static com.ticketflow.constant.Constant.CODE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;


/**
 * Feign 请求拦截器：跨服务调用时自动透传业务上下文参数。
 * Gateway → FeignClient → 下游微服务 链路中保持 code / gray 一致。
 * <p>
 * 参数来源：
 * code — 从当前 ServletRequest header 读取
 * gray — 优先请求 header，fallback 到配置的 serverGray（本地灰度标识）
 * <p>
 * 结合 RequestValidationFilter（注入）+ BaseParameterFilter（提取）+ 此拦截器（传递）
 * 形成完整的业务上下文传递机制。
 *
 * 说明：链路追踪上下文（W3C traceparent）由 OpenTelemetry Java Agent 自动注入/透传，不在此处理。
 */

@Slf4j
@AllArgsConstructor
public class FeignRequestInterceptor implements RequestInterceptor {

    private final String serverGray;

    @Override
    public void apply(RequestTemplate template) {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (Objects.nonNull(ra)) {
                ServletRequestAttributes sra = (ServletRequestAttributes) ra;
                HttpServletRequest request = sra.getRequest();
                String code = request.getHeader(CODE);
                String gray = request.getHeader(GRAY_PARAMETER);
                if (StringUtil.isEmpty(gray)) {
                    gray = serverGray;
                }
                template.header(CODE, code);
                template.header(GRAY_PARAMETER, gray);
            }
        } catch (Exception e) {
            log.error("FeignRequestInterceptor apply error", e);
        }
    }
}
