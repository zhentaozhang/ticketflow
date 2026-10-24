package com.ticketflow.feign;

import com.ticketflow.constant.Constant;
import com.ticketflow.util.StringUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部令牌透传拦截器：只对调用 base-data-service 的 Feign 请求补 {@code internalToken} 头。
 * <p>
 * 为什么需要：渠道私钥（signSecretKey/dataSecretKey/aesKey/tokenSecret）不再通过公开接口下发，
 * 网关与 user-service 改为调用 {@code /channel/data/getSecretByCode}，该接口由
 * {@link com.ticketflow.annotation.InternalApi} + 服务端内部令牌校验保护。
 * 令牌只在服务端配置里存在，绝不进入前端 bundle。
 * <p>
 * 为什么按目标服务过滤：如果对所有下游都塞这个头，令牌会随着每次服务间调用扩散到
 * order/pay/customize 等无关服务，扩大泄漏面。这里通过 {@link RequestTemplate#feignTarget()}
 * 判断目标服务名，只对 base-data-service 附加。
 */
@Slf4j
@AllArgsConstructor
public class InternalTokenFeignInterceptor implements RequestInterceptor {

    private static final String BASE_DATA_SERVICE_SUFFIX = "base-data-service";

    private final String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        try {
            if (StringUtil.isEmpty(internalToken)) {
                // 未配置内部令牌时不附加头：调用内部接口会被目标服务拒绝（fail closed），
                // 而不是静默通过。启动期由各服务的自动配置给出告警。
                return;
            }
            Target<?> target = template.feignTarget();
            String serviceName = target == null ? null : target.name();
            if (StringUtil.isEmpty(serviceName) || !serviceName.contains(BASE_DATA_SERVICE_SUFFIX)) {
                return;
            }
            template.header(Constant.INTERNAL_TOKEN, internalToken);
        } catch (Exception e) {
            log.error("InternalTokenFeignInterceptor apply error", e);
        }
    }
}
