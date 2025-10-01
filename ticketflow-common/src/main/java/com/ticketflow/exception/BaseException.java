package com.ticketflow.exception;

/**
 * 业务异常根类（继承 RuntimeException）。
 * <p>
 * 层次关系：
 * BaseException ← TicketFlowFrameException（各模块通用业务异常）
 * ← ArgumentException（参数校验异常，携带 ArgumentError 列表）
 * <p>
 * 提供 4 种构造方式：无参、仅 message、仅 cause、message+cause
 */
public class BaseException extends RuntimeException {

    public BaseException() {

    }

    public BaseException(String message) {
        super(message);
    }

    public BaseException(Throwable cause) {
        super(cause);
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public BaseException(Integer code, String message, Throwable cause) {
        super(message, cause);
    }
}
