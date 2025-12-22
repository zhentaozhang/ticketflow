package com.ticketflow.exception;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.conf.RequestTemporaryWrapper;
import com.ticketflow.enums.BaseCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 全局异常处理器，实现 ErrorWebExceptionHandler。
 * 统一拦截所有请求异常，按类型分派：
 *
 *   ResponseStatusException → 404 返回 NOT_FOUND（路径/方法信息）
 *   TicketFlowFrameException    → 业务码 + 业务消息，HTTP 200（前端按 code 处理）
 *   ArgumentException      → 参数校验失败，携带 ArgumentError 列表
 *   Exception              → 网络异常（-100），兜底保护
 *
 * 注意：TicketFlowFrameException 和 ArgumentException 走 HTTP 200 响应——
 *       前端统一按 ApiResponse.code 而非 HTTP 状态码做业务判断
 */
@Slf4j
public class GatewayDefaultExceptionHandler implements ErrorWebExceptionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        boolean exceptionFlag = false;
        RequestTemporaryWrapper requestTemporaryWrapper = new RequestTemporaryWrapper();
        if (ex instanceof ResponseStatusException) {
            ResponseStatusException responseStatusException = (ResponseStatusException)ex;
            if (responseStatusException.getStatusCode() == HttpStatus.NOT_FOUND) {
                String path = exchange.getRequest().getPath().value();
                String methodValue = exchange.getRequest().getMethod().name();
                ApiResponse<String> apiResponse = ApiResponse.error(BaseCode.NOT_FOUND.getCode(),
                        String.format(BaseCode.NOT_FOUND.getMsg(), methodValue, path));
                requestTemporaryWrapper.setApiResponse(apiResponse);
                exceptionFlag = true;
            }
        } else if (ex instanceof TicketFlowFrameException) {
            TicketFlowFrameException ticketFlowFrameException = (TicketFlowFrameException)ex;
            ApiResponse<String> apiResponse = ApiResponse.error(ticketFlowFrameException.getCode(), ticketFlowFrameException.getMessage());
            requestTemporaryWrapper.setApiResponse(apiResponse);
            exceptionFlag = true;
        } else if (ex instanceof ArgumentException) {
            ArgumentException ae = (ArgumentException)ex;
            ApiResponse<Object> apiResponse = ApiResponse.error(ae.getCode(), ae.getMessage());
            apiResponse.setData(ae.getArgumentErrorList());
            requestTemporaryWrapper.setApiResponse(apiResponse);
            exceptionFlag = true;
        } else if (ex instanceof Exception) {
            ApiResponse<String> apiResponse = ApiResponse.error(-100,"网络异常!");
            requestTemporaryWrapper.setApiResponse(apiResponse);
            exceptionFlag = true;
        }
        if (exceptionFlag) {
            response.setStatusCode(HttpStatus.OK);
        } else {
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ApiResponse<?> finalResponse = requestTemporaryWrapper.getApiResponse();
        return response
                .writeWith(Mono.fromSupplier(() -> {
                    DataBufferFactory bufferFactory = response.bufferFactory();
                    try {
                        return bufferFactory.wrap(OBJECT_MAPPER.writeValueAsBytes(finalResponse));
                    } catch (JsonProcessingException e) {
                        log.error("response error",e);
                        return bufferFactory.wrap(new byte[0]);
                    }
                }));
    }
}
