package com.couponseckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.couponseckill.common.Result;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的优惠券（按 userId 分片）。
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserCouponController {

    private final UserCouponMapper couponMapper;

    @GetMapping("/coupons")
    public Result<Page<UserCoupon>> list(@RequestHeader("X-User-Id") Long userId,
                                         @RequestParam(value = "status", required = false) Integer status,
                                         @RequestParam(value = "page", defaultValue = "1") long page,
                                         @RequestParam(value = "size", defaultValue = "20") long size) {
        ShardingContext.setUserId(userId);
        try {
            Page<UserCoupon> p = couponMapper.selectPage(new Page<>(page, size),
                    new LambdaQueryWrapper<UserCoupon>()
                            .eq(UserCoupon::getUserId, userId)
                            .eq(status != null, UserCoupon::getStatus, status)
                            .orderByDesc(UserCoupon::getCreateTime));
            return Result.ok(p);
        } finally {
            ShardingContext.clear();
        }
    }
}
