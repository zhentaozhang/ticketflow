package com.couponseckill.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端：创建秒杀活动请求。
 */
@Data
public class CreateActivityRequest {

    @NotNull(message = "券模板ID不能为空")
    private Long couponTemplateId;

    @NotBlank(message = "活动名称不能为空")
    private String activityName;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @Future(message = "结束时间必须晚于当前时间")
    private LocalDateTime endTime;

    @NotNull(message = "总库存不能为空")
    @Min(value = 1, message = "总库存至少为1")
    private Integer totalStock;

    @NotNull(message = "每人限购不能为空")
    @Min(value = 1, message = "每人限购至少为1")
    private Integer perUserLimit;
}
