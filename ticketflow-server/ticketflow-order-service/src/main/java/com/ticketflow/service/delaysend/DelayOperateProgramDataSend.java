package com.ticketflow.service.delaysend;

import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TIME;
import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TIME_UNIT;
import static com.ticketflow.service.constant.OrderConstant.DELAY_OPERATE_PROGRAM_DATA_TOPIC;

/**
 * 延迟队列发送器：订单支付成功后触发节目数据更新。
 * 支付成功后不是立即更新座位/余票，而是向延迟队列推送一条消息，
 * 延迟时间后由 DelayOperateProgramDataConsumer 消费执行。
 *
 * 延迟目的：确保 DB 事务已提交 + Redis 主从同步完成后再操作缓存
 *
 * 失败可见性：旧实现把投递异常吞在 catch 里只打一行日志，消息没进队列时
 * 节目服务 DB 会永远停在「座位未售、余票没减」，而订单侧已经是已支付——
 * 两侧永久分叉且没有任何补偿入口。现在返回 false，由调用方升级为
 * 可关联订单号的错误日志 + 指标 {@link Metrics#ORDER_PROGRAM_DATA_SEND_TOTAL}。
 */
@Slf4j
@Component
public class DelayOperateProgramDataSend {

    @Autowired
    private DelayQueueContext delayQueueContext;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 投递支付后节目数据操作消息。
     *
     * @param message 消息体（ProgramOperateDataDto 的 JSON）
     * @return true 已进入延迟队列；false 投递失败
     */
    public boolean sendMessage(String message){
        try {
            delayQueueContext.sendMessage(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_OPERATE_PROGRAM_DATA_TOPIC,
                    message, DELAY_OPERATE_PROGRAM_DATA_TIME, DELAY_OPERATE_PROGRAM_DATA_TIME_UNIT);
            BusinessMetrics.increment(meterRegistry, Metrics.ORDER_PROGRAM_DATA_SEND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_SUCCESS);
            return true;
        }catch (Exception e) {
            log.error("支付后节目数据延迟消息投递失败 message : {}",message,e);
            BusinessMetrics.increment(meterRegistry, Metrics.ORDER_PROGRAM_DATA_SEND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_FAIL);
            return false;
        }
    }
}
