package com.ticketflow.exception;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.enums.BaseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 下游微服务全局异常处理器（@RestControllerAdvice）。
 * 按异常类型分三层处理：
 * TicketFlowFrameException → 业务异常，返回自定义 code + message
 * MethodArgumentNotValidException → Spring 参数校验失败，返回 PARAMETER_ERROR + 字段级错误列表
 * Throwable → 兜底：统一返回 -100 "网络异常"
 * <p>
 * 与 Gateway 层的 GatewayDefaultExceptionHandler 对应——Gateway 层捕获后
 * 以 HTTP 200 + ApiResponse.code 形式传递，下游服务直接抛 TicketFlowFrameException 即可。
 */
@Slf4j
@RestControllerAdvice
public class DefaultExceptionHandler {

    /**
     * 业务异常
     *
     */
    @ExceptionHandler(value = TicketFlowFrameException.class)
    public ApiResponse<String> toolkitExceptionHandler(HttpServletRequest request, TicketFlowFrameException ticketFlowFrameException) {
        log.error("业务异常 错误信息 : {} method : {} url : {} query : {} ", ticketFlowFrameException.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), ticketFlowFrameException);
        return ApiResponse.error(ticketFlowFrameException.getCode(), ticketFlowFrameException.getMessage());
    }

    /**
     * 参数验证异常
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ApiResponse<List<ArgumentError>> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        log.error("参数验证异常 错误信息 : {} method : {} url : {} query : {} ", ex.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), ex);
        BindingResult bindingResult = ex.getBindingResult();
        List<ArgumentError> argumentErrorList =
                bindingResult.getFieldErrors()
                        .stream()
                        .map(fieldError -> {
                            ArgumentError argumentError = new ArgumentError();
                            argumentError.setArgumentName(fieldError.getField());
                            argumentError.setMessage(fieldError.getDefaultMessage());
                            return argumentError;
                        }).collect(Collectors.toList());
        return ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(), argumentErrorList);
    }

    /**
     * 拦截未捕获异常
     */
    @ExceptionHandler(value = Throwable.class)
    public ApiResponse<String> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("全局异常 错误信息 : {} method : {} url : {} query : {} ", throwable.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), throwable);
        return ApiResponse.error();
    }

    private String getRequestUrl(HttpServletRequest request) {
        return request.getRequestURL().toString();
    }

    private String getRequestQuery(HttpServletRequest request) {
        return request.getQueryString();
    }
}
