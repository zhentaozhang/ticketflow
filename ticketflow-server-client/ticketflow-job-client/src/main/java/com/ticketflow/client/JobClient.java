package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.JobCallBackDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.validation.Valid;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 任务调度服务 Feign 客户端。
 * 各业务服务通过此接口上报 XXL-Job 执行状态
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"job-service",fallback = JobClientFallback.class)
//                ↑ Feign 声明式客户端，value = Nacos 服务名  ↑ 熔断降级类
public interface JobClient {
    
    /**
     * 上报任务状态
     * @param dto 参数
     * @return 结果
     * */
    @RequestMapping(value = "jobRunRecord/callBack", method = RequestMethod.POST)
    ApiResponse<Boolean> callBack(@Valid @RequestBody JobCallBackDto dto);
}
