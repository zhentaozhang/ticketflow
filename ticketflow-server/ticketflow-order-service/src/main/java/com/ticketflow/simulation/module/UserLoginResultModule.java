package com.ticketflow.simulation.module;

import com.ticketflow.vo.UserLoginVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 压测用户登录结果模块。模拟用户登录接口返回结果的数据结构。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserLoginResultModule extends ApiResponseModule{

    private UserLoginVo data;
}
