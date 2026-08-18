package com.ticketflow.service;

import com.ticketflow.client.CouponClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.CouponOperateDto;
import com.ticketflow.dto.CouponOrderCancelDto;
import com.ticketflow.dto.CouponOrderCreateDto;
import com.ticketflow.toolkit.SnowflakeIdGenerator;
import com.ticketflow.vo.CouponOperateVo;
import com.ticketflow.vo.CouponOrderVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 券订单服务（M5 集成验证）：order-service 通过 Feign 契约调用 coupon-service，
 * 完整走通 锁券 → 抵扣金额计算 → 模拟支付 → 核销 / 取消退券 三态流转。
 * <p>
 * 说明：真实购票主链路（program→order create）的券支持按同一契约接入，
 * 本服务提供独立契约接口验证 lock/use/return 正确性，不修改 V5 主链路。
 */
@Slf4j
@Service
public class CouponOrderService {

    /** coupon 服务成功码（coupon 侧 Result.code=200；现有 ApiResponse 成功码为 0） */
    private static final int COUPON_SUCCESS_CODE = 200;

    @Autowired
    private CouponClient couponClient;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 创建券订单：锁券 → 计算抵扣 → 模拟支付成功 → 核销。
     */
    public CouponOrderVo createCouponOrder(CouponOrderCreateDto dto) {
        String orderNo = String.valueOf(snowflakeIdGenerator.nextId());

        // 1) 锁券（防并发使用），返回面额快照
        CouponOperateDto lockDto = buildOperateDto(dto.getCouponNo(), dto.getUserId(), orderNo);
        ApiResponse<CouponOperateVo> lockResp = couponClient.lock(lockDto);
        if (lockResp.getCode() != COUPON_SUCCESS_CODE || lockResp.getData() == null) {
            log.warn("[coupon-order] lock fail: code={} msg={}", lockResp.getCode(), lockResp.getMessage());
            throw new IllegalStateException("锁券失败: " + lockResp.getMessage());
        }
        BigDecimal discount = lockResp.getData().getAmount();

        // 2) 计算抵扣后实付（满减券：实付 = max(0, 原价 - 面额)）
        BigDecimal payAmount = dto.getAmount().subtract(discount);
        if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
            payAmount = BigDecimal.ZERO;
        }

        // 3) 模拟支付成功 → 核销
        ApiResponse<Void> useResp = couponClient.use(buildOperateDto(dto.getCouponNo(), dto.getUserId(), orderNo));
        if (useResp.getCode() != COUPON_SUCCESS_CODE) {
            log.error("[coupon-order] use fail: code={} msg={}", useResp.getCode(), useResp.getMessage());
            throw new IllegalStateException("券核销失败: " + useResp.getMessage());
        }

        log.info("[coupon-order] created orderNo={} couponNo={} original={} discount={} pay={}",
                orderNo, dto.getCouponNo(), dto.getAmount(), discount, payAmount);

        CouponOrderVo vo = new CouponOrderVo();
        vo.setOrderNo(orderNo);
        vo.setCouponNo(dto.getCouponNo());
        vo.setOriginalAmount(dto.getAmount());
        vo.setDiscountAmount(discount);
        vo.setPayAmount(payAmount);
        vo.setStatus("PAID");
        return vo;
    }

    /**
     * 取消券订单（模拟支付失败/订单取消）：退券。
     */
    public CouponOrderVo cancelCouponOrder(CouponOrderCancelDto dto) {
        ApiResponse<Void> resp = couponClient.returnCoupon(buildOperateDto(dto.getCouponNo(), dto.getUserId(), dto.getOrderNo()));
        if (resp.getCode() != COUPON_SUCCESS_CODE) {
            log.warn("[coupon-order] cancel fail: code={} msg={}", resp.getCode(), resp.getMessage());
            throw new IllegalStateException("退券失败: " + resp.getMessage());
        }
        log.info("[coupon-order] cancelled orderNo={} couponNo={}", dto.getOrderNo(), dto.getCouponNo());

        CouponOrderVo vo = new CouponOrderVo();
        vo.setOrderNo(dto.getOrderNo());
        vo.setCouponNo(dto.getCouponNo());
        vo.setStatus("CANCELLED");
        return vo;
    }

    private CouponOperateDto buildOperateDto(String couponNo, Long userId, String orderNo) {
        CouponOperateDto dto = new CouponOperateDto();
        dto.setCouponNo(couponNo);
        dto.setUserId(userId);
        dto.setOrderNo(orderNo);
        return dto;
    }
}
