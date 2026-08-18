package com.couponseckill.controller;

import com.couponseckill.common.Result;
import com.couponseckill.dto.CouponOperateRequest;
import com.couponseckill.dto.CouponOperateResult;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.service.CouponUseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用券接口（与 order-service 的集成契约，docs/01-技术设计.md §12.3）。
 * 独立阶段直接调用；集成阶段由 order-service 通过 Feign/RPC 调用。
 */
@RestController
@RequestMapping("/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponUseService couponUseService;

    @PostMapping("/lock")
    public Result<CouponOperateResult> lock(@Valid @RequestBody CouponOperateRequest req) {
        return Result.ok(couponUseService.lockCoupon(req.getCouponNo(), req.getUserId(), req.getOrderNo()));
    }

    @PostMapping("/use")
    public Result<Void> use(@Valid @RequestBody CouponOperateRequest req) {
        couponUseService.useCoupon(req.getCouponNo(), req.getUserId(), req.getOrderNo());
        return Result.ok();
    }

    @PostMapping("/return")
    public Result<Void> returnCoupon(@Valid @RequestBody CouponOperateRequest req) {
        couponUseService.returnCoupon(req.getCouponNo(), req.getUserId(), req.getOrderNo());
        return Result.ok();
    }

    @GetMapping("/detail")
    public Result<UserCoupon> detail(@RequestParam("couponNo") String couponNo,
                                     @RequestParam("userId") Long userId) {
        return Result.ok(couponUseService.getCoupon(couponNo, userId));
    }
}
