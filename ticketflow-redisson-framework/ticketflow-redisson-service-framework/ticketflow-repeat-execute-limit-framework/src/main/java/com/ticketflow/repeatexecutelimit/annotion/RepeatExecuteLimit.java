package com.ticketflow.repeatexecutelimit.annotion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * 防重复执行注解——基于 Redisson 分布式锁实现幂等。
 * <p>
 * 在指定 time 内，相同的 name+keys 只会执行一次，
 * 后续请求直接抛出异常或返回
 * <p>
 * 配合 RepeatExecuteLimitAspect 使用
 */
@Target(value = {ElementType.TYPE, ElementType.METHOD})
@Retention(value = RetentionPolicy.RUNTIME)
public @interface RepeatExecuteLimit {

    /**
     * 业务名称
     *
     * @return name
     */
    String name() default "";

    /**
     * key设置
     *
     * @return key
     */
    String[] keys();

    /**
     * 在多长时间内一直保持幂等，如果不配置则以执行方法为准
     *
     */
    long durationTime() default 0L;

    /**
     * 当消息执行已经出发防重复执行的限制时，提示信息
     *
     */
    String message() default "提交频繁，请稍后重试";

}
