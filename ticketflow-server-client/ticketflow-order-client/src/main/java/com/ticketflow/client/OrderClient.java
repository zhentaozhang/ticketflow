package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AccountOrderCountDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.vo.AccountOrderCountVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 订单服务 Feign 客户端。
 * program-service 和 gateway 通过此接口创建订单、查询账户下单数量
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"order-service")
//                ↑ Nacos 服务名  ↑ program-service 下单时调用
public interface OrderClient {
    
    /** 创建订单（program-service → order-service） */
    @PostMapping("/order/create")
    ApiResponse<String> create(OrderCreateDto dto);
    
    /** 限购检查：账户下某个节目的已购订单数量 */
    @PostMapping("/order/account/order/count")
    ApiResponse<AccountOrderCountVo> accountOrderCount(AccountOrderCountDto dto);
}
