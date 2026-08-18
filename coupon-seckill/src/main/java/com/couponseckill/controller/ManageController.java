package com.couponseckill.controller;

import com.couponseckill.common.Result;
import com.couponseckill.dto.CreateActivityRequest;
import com.couponseckill.dto.CreateTemplateRequest;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.service.ActivityCacheService;
import com.couponseckill.service.ManageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口。
 */
@RestController
@RequestMapping("/manage")
@RequiredArgsConstructor
public class ManageController {

    private final ManageService manageService;
    private final ActivityCacheService cacheService;

    @PostMapping("/template")
    public Result<CouponTemplate> createTemplate(@Valid @RequestBody CreateTemplateRequest req) {
        return Result.ok(manageService.createTemplate(req));
    }

    @PostMapping("/activity")
    public Result<FlashSaleActivity> createActivity(@Valid @RequestBody CreateActivityRequest req) {
        return Result.ok(manageService.createActivity(req));
    }

    @PostMapping("/activity/{id}/publish")
    public Result<FlashSaleActivity> publish(@PathVariable("id") Long id) {
        return Result.ok(manageService.publish(id));
    }

    @PostMapping("/activity/{id}/offline")
    public Result<FlashSaleActivity> offline(@PathVariable("id") Long id) {
        return Result.ok(manageService.offline(id));
    }

    @PostMapping("/activity/{id}/stock")
    public Result<FlashSaleActivity> adjustStock(@PathVariable("id") Long id, @RequestParam("delta") int delta) {
        return Result.ok(manageService.adjustStock(id, delta));
    }

    @GetMapping("/activity/{id}")
    public Result<FlashSaleActivity> getActivity(@PathVariable("id") Long id) {
        return Result.ok(manageService.getActivity(id));
    }

    @GetMapping("/activity/list")
    public Result<List<FlashSaleActivity>> list() {
        return Result.ok(manageService.listActivities());
    }

    @GetMapping("/activity/{id}/cache")
    public Result<Map<String, Object>> cacheStatus(@PathVariable("id") Long id) {
        return Result.ok(Map.of(
                "meta", cacheService.getMetaJson(id),
                "redisStock", cacheService.getStock(id)));
    }
}
