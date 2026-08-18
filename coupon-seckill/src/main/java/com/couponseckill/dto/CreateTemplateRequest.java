package com.couponseckill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端：创建券模板请求。
 */
@Data
public class CreateTemplateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String name;

    /** 1满减 2折扣 */
    @NotNull(message = "券类型不能为空")
    private Integer type;

    @NotNull(message = "面额不能为空")
    @DecimalMin(value = "0.01", message = "面额必须大于0")
    private BigDecimal amount;

    @NotNull(message = "使用门槛不能为空")
    @DecimalMin(value = "0.00", message = "门槛不能为负")
    private BigDecimal minAmount;

    /** 1固定时段 2领取后N天 */
    @NotNull(message = "有效期类型不能为空")
    private Integer validType;

    private LocalDateTime validStart;

    private LocalDateTime validEnd;

    @Min(value = 1, message = "有效天数至少1天")
    private Integer validDays;

    private Integer scope;
}
