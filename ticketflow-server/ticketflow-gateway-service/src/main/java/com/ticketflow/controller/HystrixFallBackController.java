package com.ticketflow.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 熔断降级 API——Hystrix 熔断时的 fallback 处理
 */
@RestController
public class HystrixFallBackController {

    @RequestMapping(value = "/fallBackHandler")
    public String fallBackHandler() {
        return "熔断器执行";
    }
}
