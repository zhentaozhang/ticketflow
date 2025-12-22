package com.ticketflow.conf;

import com.ticketflow.common.ApiResponse;
import lombok.Data;

import java.util.Map;

/**
 * 请求临时包装。网关层用于暂存请求解析过程中的中间数据。
 */
@Data
public class RequestTemporaryWrapper {
    
    private Map<String,String> map;
    
    private ApiResponse<?> apiResponse;
}
