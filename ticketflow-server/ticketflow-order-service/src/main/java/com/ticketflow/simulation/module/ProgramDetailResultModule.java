package com.ticketflow.simulation.module;

import com.ticketflow.vo.ProgramVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 压测节目详情结果模块。模拟节目详情接口返回结果的数据结构。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ProgramDetailResultModule extends ApiResponseModule{

    private ProgramVo data;
}
