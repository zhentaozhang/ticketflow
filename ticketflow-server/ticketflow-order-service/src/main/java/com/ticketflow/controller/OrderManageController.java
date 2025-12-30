package com.ticketflow.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.OrderPageManageDto;
import com.ticketflow.dto.RecordManageDto;
import com.ticketflow.service.OrderManageService;
import com.ticketflow.vo.DiscardOrderManageVo;
import com.ticketflow.vo.OrderManageVo;
import com.ticketflow.vo.RecordOrderManageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单管理 API——运营端订单查询/取消/对账记录
 */
@RestController
@RequestMapping("/order/manage")
@Tag(name = "order/manage", description = "订单")
public class OrderManageController {
    
    @Autowired
    private OrderManageService orderManageService;

    

    @Operation(summary  = "操作记录分页列表")
    @PostMapping(value = "/record/page")
    public ApiResponse<IPage<RecordOrderManageVo>> recordPage(@Valid @RequestBody RecordManageDto recordManageDto) {
        return ApiResponse.ok(orderManageService.recordPage(recordManageDto));
    }
    
    @Operation(summary  = "查看订单分页列表")
    @PostMapping(value = "/order/page")
    public ApiResponse<IPage<OrderManageVo>> orderPage(@Valid @RequestBody OrderPageManageDto orderPageManageDto) {
        return ApiResponse.ok(orderManageService.orderPage(orderPageManageDto));
    }
    
    @Operation(summary  = "查看废弃订单分页列表")
    @PostMapping(value = "/discard/order/page")
    public ApiResponse<IPage<DiscardOrderManageVo>> discardOrderPage(@Valid @RequestBody OrderPageManageDto orderPageManageDto) {
        return ApiResponse.ok(orderManageService.discardOrderPage(orderPageManageDto));
    }
}
