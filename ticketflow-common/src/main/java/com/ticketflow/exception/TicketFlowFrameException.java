package com.ticketflow.exception;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.enums.BaseCode;
import lombok.Data;

/**
 * 统一业务异常，整个项目的异常传递标准载体。
 * 支持 4 种构造方式：
 * (Integer code, String message) — 最常用，直接携带业务码
 * (BaseCode enum) — 通过枚举预定义码+消息
 * (ApiResponse) — 从已有响应对象构建
 * (Integer code, String message, Throwable cause) — 保留堆栈信息
 * <p>
 * 原则：业务异常应优先使用 BaseCode 枚举构造，保持码值集中管理
 */
@Data
public class TicketFlowFrameException extends BaseException {

    private Integer code;

    private String message;

    public TicketFlowFrameException() {
        super();
    }

    public TicketFlowFrameException(String message) {
        super(message);
    }


    public TicketFlowFrameException(String code, String message) {
        super(message);
        this.code = Integer.parseInt(code);
        this.message = message;
    }

    public TicketFlowFrameException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public TicketFlowFrameException(BaseCode baseCode) {
        super(baseCode.getMsg());
        this.code = baseCode.getCode();
        this.message = baseCode.getMsg();
    }

    public TicketFlowFrameException(ApiResponse apiResponse) {
        super(apiResponse.getMessage());
        this.code = apiResponse.getCode();
        this.message = apiResponse.getMessage();
    }

    public TicketFlowFrameException(Throwable cause) {
        super(cause);
    }

    public TicketFlowFrameException(String message, Throwable cause) {
        super(message, cause);
        this.message = message;
    }

    public TicketFlowFrameException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }
}
