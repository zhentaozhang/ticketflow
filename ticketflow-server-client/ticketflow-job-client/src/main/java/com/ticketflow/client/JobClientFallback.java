package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.JobCallBackDto;
import com.ticketflow.enums.BaseCode;
import org.springframework.stereotype.Component;

/**
 * Job服务Feign降级。任务调度服务不可用时的降级处理。
 */
@Component
// 实现 JobClient 接口，Feign 在熔断时调用这里的实现而不是抛异常
public class JobClientFallback implements JobClient {
    
    @Override
    public ApiResponse<Boolean> callBack(final JobCallBackDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 统一返回错误码，调用方自行判断
    }
}
