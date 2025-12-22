package com.ticketflow.pro.limit;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;

/**
 * 限流器配置属性。从配置中心读取的网关限流参数，支持运行时动态调整。
 * 属性: rate.switch(开关), rate.permits(每秒许可数)
 */
@Data
public class RateLimiterProperty {
    
    @Value("${rate.switch:false}")
    private Boolean rateSwitch;

    @Value("${rate.permits:200}")
    private Integer ratePermits;
}
