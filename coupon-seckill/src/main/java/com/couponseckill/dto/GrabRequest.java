package com.couponseckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 抢购请求。
 */
@Data
public class GrabRequest {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    /** 客户端生成的幂等键（UUID），同一请求重试使用相同值 */
    @NotBlank(message = "requestId不能为空")
    private String requestId;
}
