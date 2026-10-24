package com.ticketflow.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内部接口标记。
 * <p>
 * 被该注解标注的 Controller 方法只能由内部服务调用：调用方必须在请求头
 * {@link com.ticketflow.constant.Constant#INTERNAL_TOKEN} 中携带与目标服务一致的内部令牌，
 * 否则请求会被拦截器直接拒绝（{@link com.ticketflow.enums.BaseCode#INTERNAL_API_FORBIDDEN}）。
 * <p>
 * 适用场景：下发密钥、变更渠道状态等"绝不能暴露给浏览器/第三方"的管理类接口。
 * 与网关的渠道签名校验不同，内部令牌只在服务端配置中持有，不进入前端 bundle，
 * 因此即使攻击者能直连服务端口，也调不动这些接口。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalApi {
}
