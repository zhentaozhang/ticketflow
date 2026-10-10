package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSONException;
import com.ticketflow.exception.TicketFlowFrameException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 建单消费者的错误处理器配置。
 * <p>
 * 背景：offset 关闭自动提交（{@code enable-auto-commit: false} + {@code ack-mode: batch}）之后，
 * 只有这一批消息全部处理成功、没有异常抛出，容器才会提交 offset。
 * 抛出的异常会进入这里，按类型分流：
 * <ul>
 *   <li><b>可重试异常</b>（Feign 超时、SQL 异常、Redis 抖动等）→ 退避重试。
 *       这类故障往往几百毫秒后就恢复了，不重试就等于把一次可恢复的故障变成"用户丢单"；</li>
 *   <li><b>不可重试异常</b>（{@link TicketFlowFrameException}，业务性失败，例如"座位不是未售"）
 *       → 不浪费时间重试，直接进恢复回调；</li>
 *   <li><b>重试穷尽</b> → 恢复回调 {@link CreateOrderDiscardRecorder} 写 DISCARD_ORDER 留痕，
 *       资源回滚与状态收敛交给对账任务兜底。</li>
 * </ul>
 * 所以这条链路上的可靠性是三层：<b>至少一次（重试）+ 幂等（吸收重复）+ 对账（兜住重试穷尽）</b>。
 */
@Configuration
public class OrderKafkaErrorHandlerConfig {

    /**
     * 重试总时长的上限，刻意压在消费端 60 秒丢弃闸（{@code MESSAGE_DELAY_TIME}）以内。
     * <p>
     * 两道闸是咬合的：重试不会超过这个时间，所以不会把积压无限拖长；
     * 而如果重试期间消息已经老过 60 秒，下一次投递时会被消费者自己按"用户已经不等了"丢弃。
     * <p>
     * 包级可见（而非 private）的目的：让同包测试能把“它必须小于消费端丢弃闸”这个耦合断言下来
     * （见 {@code CreateOrderConsumerTest#重试总时长必须小于消费端丢弃闸}）。
     */
    static final long RETRY_MAX_ELAPSED_MS = 50_000L;

    /** 首次重试间隔 200ms，之后指数退避（200 → 400 → 800 …），直到上面的总时长上限。 */
    private static final long INITIAL_INTERVAL_MS = 200L;

    private static final double BACK_OFF_MULTIPLIER = 2.0;

    /**
     * Spring Boot 的自动配置会检测到 {@code CommonErrorHandler} 类型的 Bean 并用在消费容器上，
     * 所以这个 Bean 一声明，48 个消费线程都会走这套分流逻辑。
     */
    @Bean
    public DefaultErrorHandler kafkaConsumerErrorHandler(CreateOrderDiscardRecorder discardRecorder) {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, BACK_OFF_MULTIPLIER);
        backOff.setMaxElapsedTime(RETRY_MAX_ELAPSED_MS);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(discardRecorder, backOff);
        // 业务性失败重试没有意义：重试一百次结果一样，直接进留痕 + 对账
        errorHandler.addNotRetryableExceptions(TicketFlowFrameException.class);
        // 消息体本身就解析不了：也是确定性失败。而且不退避重试很重要——
        // 一条坏消息会在重试期间把这个分区堵住，后面的消息会被推迟，
        // 推过 60 秒就可能被消费端的丢弃闸误丢。
        errorHandler.addNotRetryableExceptions(JSONException.class);
        // 留痕成功即视为这条消息已处理完毕，提交它的 offset；
        // 否则消息会在下次重启时被再次投递，而它已经无法处理了
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }
}
