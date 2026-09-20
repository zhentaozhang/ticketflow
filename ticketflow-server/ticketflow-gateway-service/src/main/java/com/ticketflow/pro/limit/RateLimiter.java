package com.ticketflow.pro.limit;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;

import java.util.concurrent.Semaphore;

/**
 * 自适应信号量限流器，为 Gateway 入口提供粗粒度熔断。
 * 基于 Semaphore（非公平），非阻塞 tryAcquire：许可证耗尽时立即抛出
 * OPERATION_IS_TOO_FREQUENT，不排队等待。
 *
 * 位置：filter() 最外层，优先于身份验证和业务限流。
 * 说明：运行在 WebFlux 事件循环上，必须避免阻塞（原 tryAcquire(1, SECONDS)
 * 会在许可耗尽时阻塞事件循环最多 1 秒）。
 */
public class RateLimiter {
    
    private final Semaphore semaphore;
    
    public RateLimiter(int maxPermitsPerSecond) {
        this.semaphore = new Semaphore(maxPermitsPerSecond);
    }
    
    public void acquire() {
        if (!semaphore.tryAcquire()) {
            throw new TicketFlowFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER);
        }
    }
    
    public void release() {
        semaphore.release();
    }
}
