package com.ticketflow.simulation.module;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 压测创建订单结果模块。模拟下单接口返回结果的数据结构。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CreateProgramOrderResultModule extends ApiResponseModule{

    private String data;
}
