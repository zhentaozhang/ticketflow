package com.ticketflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 异常消息执行DTO。封装待处理的异常消息内容，用于分发到对应的异常处理器。
 */
@Data
public class ExecuteExceptionMessageDto {

    @NotNull
    private Long messageId;
}
