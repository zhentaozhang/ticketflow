package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AccountOrderCountDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.AccountOrderCountVo;
import org.springframework.stereotype.Component;

/**
 * 订单服务Feign降级。订单服务不可用时的降级处理。
 */
@Component
public class OrderClientFallback implements OrderClient {
    
    @Override
    public ApiResponse<String> create(final OrderCreateDto orderCreateDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 创建订单失败 → 用户看到下单失败
    }
    
    @Override
    public ApiResponse<AccountOrderCountVo> accountOrderCount(final AccountOrderCountDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<Void> reloadRouteMappingCache() {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
