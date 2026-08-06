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
 * 支持类级声明：类注解作为默认配置，方法级注解优先覆盖（同 @Transactional 语义）。
 * 幂等标记在业务成功后写入，晚于同方法上 @Transactional 的提交（切面 @Order(-11)
 * 低于事务拦截器默认顺序）；若 @Transactional 标注在更外层方法，标记可能早于提交。
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
     * 业务执行成功后保持幂等的时间（秒）。
     * <p>
     * durationTime &gt; 0：业务成功后在 Redis 写入幂等标记并保持 durationTime 秒，
     * 期间相同 name+keys 的请求直接快速失败；
     * durationTime = 0（默认）：仅防并发（锁持有期间互斥），不写入幂等标记，
     * 业务完成后相同请求可再次执行。Kafka 消费幂等等场景应显式配置 durationTime。
     */
    long durationTime() default 0L;

    /**
     * 当消息执行已经出发防重复执行的限制时，提示信息
     *
     */
    String message() default "提交频繁，请稍后重试";

}
