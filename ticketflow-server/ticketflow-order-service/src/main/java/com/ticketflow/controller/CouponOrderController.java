package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.CouponOrderCancelDto;
import com.ticketflow.dto.CouponOrderCreateDto;
import com.ticketflow.service.CouponOrderService;
import com.ticketflow.vo.CouponOrderVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 券订单接口（M5 集成验证）：order-service 调用 coupon-service 的用券契约。
 */
@RestController
@RequestMapping("/order/coupon")
@Tag(name = "couponOrder", description = "优惠券订单(集成验证)")
public class CouponOrderController {

    @Autowired
    private CouponOrderService couponOrderService;

    /**
     * 创建券订单：锁券 → 抵扣 → 模拟支付 → 核销。
     */
    @Operation(summary = "创建券订单(锁券-抵扣-支付-核销)")
    @PostMapping("/order")
    public ApiResponse<CouponOrderVo> create(@Valid @RequestBody CouponOrderCreateDto dto) {
        return ApiResponse.ok(couponOrderService.createCouponOrder(dto));
    }

    /**
     * 取消券订单：退券。
     */
    @Operation(summary = "取消券订单(退券)")
    @PostMapping("/cancel")
    public ApiResponse<CouponOrderVo> cancel(@Valid @RequestBody CouponOrderCancelDto dto) {
        return ApiResponse.ok(couponOrderService.cancelCouponOrder(dto));
    }
}
