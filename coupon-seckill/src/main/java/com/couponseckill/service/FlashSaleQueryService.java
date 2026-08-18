package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.dto.GrabResult;
import com.couponseckill.entity.FlashSaleOrder;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.mapper.FlashSaleOrderMapper;
import com.couponseckill.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 抢购结果查询：Redis 结果缓存优先，DB 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleQueryService {

    private final FlashSaleResultService resultService;
    private final FlashSaleOrderMapper orderMapper;
    private final UserCouponMapper couponMapper;

    public GrabResult queryResult(Long userId, Long activityId) {
        // 1) Redis 结果缓存命中
        String cached = resultService.getCachedResult(userId, activityId);
        if (cached != null) {
            GrabResult hit = parseCached(cached);
            if (hit != null) {
                return hit;
            }
        }

        // 2) DB 兜底（按 userId 分片）
        ShardingContext.setUserId(userId);
        try {
            FlashSaleOrder order = orderMapper.selectOne(new LambdaQueryWrapper<FlashSaleOrder>()
                    .eq(FlashSaleOrder::getActivityId, activityId)
                    .eq(FlashSaleOrder::getUserId, userId)
                    .orderByDesc(FlashSaleOrder::getCreateTime)
                    .last("LIMIT 1"));
            if (order == null) {
                return GrabResult.none();
            }
            if (order.getStatus() == FlashSaleOrder.STATUS_PROCESSING) {
                return GrabResult.processing(order.getOrderNo());
            }
            if (order.getStatus() == FlashSaleOrder.STATUS_ISSUED && order.getCouponId() != null) {
                UserCoupon coupon = couponMapper.selectById(order.getCouponId());
                if (coupon != null) {
                    // 顺手回写结果缓存
                    resultService.writeSuccess(userId, activityId, coupon);
                    return toSuccess(coupon, order.getOrderNo());
                }
            }
            return GrabResult.fail("发券失败，系统将自动处理，请稍后重试");
        } finally {
            ShardingContext.clear();
        }
    }

    private GrabResult toSuccess(UserCoupon coupon, String orderNo) {
        GrabResult r = new GrabResult();
        r.setGrabStatus("SUCCESS");
        r.setOrderNo(orderNo);
        r.setCouponNo(coupon.getCouponNo());
        r.setAmount(coupon.getAmount());
        r.setMinAmount(coupon.getMinAmount());
        r.setValidStart(coupon.getValidStart());
        r.setValidEnd(coupon.getValidEnd());
        r.setMessage("抢购成功");
        return r;
    }

    private GrabResult parseCached(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!FlashSaleResultService.STATUS_SUCCESS.equals(node.get("status").asText())) {
                return null;
            }
            GrabResult r = new GrabResult();
            r.setGrabStatus("SUCCESS");
            r.setCouponNo(node.get("couponNo").asText());
            r.setAmount(new java.math.BigDecimal(node.get("amount").asText()));
            r.setMinAmount(new java.math.BigDecimal(node.get("minAmount").asText()));
            r.setValidStart(java.time.LocalDateTime.parse(node.get("validStart").asText()));
            r.setValidEnd(java.time.LocalDateTime.parse(node.get("validEnd").asText()));
            r.setMessage("抢购成功");
            return r;
        } catch (Exception e) {
            log.warn("[result-parse-fail] {}", json, e);
            return null;
        }
    }
}
