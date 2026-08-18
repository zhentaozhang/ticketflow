package com.couponseckill.controller;

import com.couponseckill.common.Result;
import com.couponseckill.dto.GrabRequest;
import com.couponseckill.dto.GrabResult;
import com.couponseckill.service.FlashSaleGrabService;
import com.couponseckill.service.FlashSaleQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端秒杀接口。
 */
@RestController
@RequestMapping("/flash-sale")
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleGrabService grabService;
    private final FlashSaleQueryService queryService;

    /**
     * 抢购。
     * X-User-Id 由登录态提供（集成阶段由网关/鉴权注入）。
     */
    @PostMapping("/grab")
    public Result<GrabResult> grab(@RequestHeader("X-User-Id") Long userId,
                                   @Valid @RequestBody GrabRequest req) {
        return Result.ok(grabService.grab(userId, req));
    }

    /**
     * 抢购结果查询（客户端轮询）。
     */
    @GetMapping("/result")
    public Result<GrabResult> result(@RequestHeader("X-User-Id") Long userId,
                                     @RequestParam("activityId") Long activityId) {
        return Result.ok(queryService.queryResult(userId, activityId));
    }
}
