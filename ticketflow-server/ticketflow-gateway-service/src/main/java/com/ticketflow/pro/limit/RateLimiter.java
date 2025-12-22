package com.ticketflow.pro.limit;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 自适应信号量限流器，为 Gateway 入口提供粗粒度熔断。
 * 基于 Semaphore（非公平），tryAcquire 超时 = 1 秒。
 * 许可证耗尽时直接抛出 OPERATION_IS_TOO_FREQUENT 异常，不再排队等待。
 *
 * 位置：filter() 最外层，优先于身份验证和业务限流。
 * 适用于整体流量超出水位时的快速失败（fail-fast）保底。
 * 细粒度接口级限流由 ApiRestrictService（Lua 滑动窗口）处理。
 */
public class RateLimiter {
    
    private final Semaphore semaphore;
    private final TimeUnit timeUnit;
    
    public RateLimiter(int maxPermitsPerSecond) {
        this.timeUnit = TimeUnit.SECONDS;
        this.semaphore = new Semaphore(maxPermitsPerSecond);
    }
    
    public void acquire() throws InterruptedException {
        if (!semaphore.tryAcquire(1, timeUnit)) {
            throw new TicketFlowFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER);
        }
    }
    
    public void release() {
        semaphore.release();
    }
}
