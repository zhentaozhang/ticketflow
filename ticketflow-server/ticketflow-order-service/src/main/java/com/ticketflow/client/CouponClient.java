package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.CouponOperateDto;
import com.ticketflow.vo.CouponOperateVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 优惠券服务 Feign 客户端（M5 集成：order-service → ticketflow-coupon-service）。
 * <p>
 * 对接契约（docs/coupon-seckill/docs/01-技术设计.md §12.3）：
 * lock/use/return 三态流转，coupon 侧统一返回 Result{code=200 成功}，
 * 与现有 ApiResponse{code=0 成功} 语义不同，调用方按 code==200 判断。
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "coupon-service",
        contextId = "couponClient")
public interface CouponClient {

    /**
     * 锁券：下单时调用，返回面额快照用于订单金额计算。
     */
    @PostMapping("/coupon/lock")
    ApiResponse<CouponOperateVo> lock(CouponOperateDto dto);

    /**
     * 核销：支付成功后调用。
     */
    @PostMapping("/coupon/use")
    ApiResponse<Void> use(CouponOperateDto dto);

    /**
     * 退回：订单取消/支付超时调用。
     */
    @PostMapping("/coupon/return")
    ApiResponse<Void> returnCoupon(CouponOperateDto dto);
}
