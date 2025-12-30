package com.ticketflow.simulation.module;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 压测响应结果模块。模拟API响应结果的数据结构。
 */
@Data
public class ApiResponseModule {

    @Schema(name ="code", type ="Integer", description ="响应码 0:成功 其余:失败")
    private Integer code;

    @Schema(name ="message", type ="String", description ="错误信息")
    private String message;
}
