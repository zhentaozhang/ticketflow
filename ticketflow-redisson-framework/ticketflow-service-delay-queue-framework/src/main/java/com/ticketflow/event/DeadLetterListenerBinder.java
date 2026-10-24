package com.ticketflow.event;

import com.ticketflow.context.DelayQueueBasePart;
import com.ticketflow.core.DeadLetterListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 把业务服务注册的 {@link DeadLetterListener} 绑定到 DelayQueueBasePart。
 *
 * 必须在 ApplicationStartedEvent 之前完成：DelayQueueInitHandler 在该事件里才会
 * 创建 DelayConsumerQueue 并读取 listener，晚于事件注册则监听器不生效。
 */
@Slf4j
public class DeadLetterListenerBinder {

    public DeadLetterListenerBinder(DelayQueueBasePart delayQueueBasePart,
                                    ObjectProvider<DeadLetterListener> deadLetterListeners) {
        DeadLetterListener listener = deadLetterListeners.getIfAvailable();
        if (listener == null) {
            log.info("未注册延迟队列死信监听器，死信仍落 Redis 与日志，但不会产出指标");
            return;
        }
        delayQueueBasePart.setDeadLetterListener(listener);
        log.info("延迟队列死信监听器已绑定 : {}", listener.getClass().getName());
    }
}
