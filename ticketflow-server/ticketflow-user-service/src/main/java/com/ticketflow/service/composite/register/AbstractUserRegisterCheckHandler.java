package com.ticketflow.service.composite.register;


import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.initialize.impl.composite.AbstractComposite;


/**
 * 用户注册组合验证链顶层抽象。
 * 模板方法：setOrder → 排序，execute 由子类实现具体校验
 */
public abstract class AbstractUserRegisterCheckHandler extends AbstractComposite<UserRegisterDto> {
    // 固定 type：属于"用户注册"这棵树
    @Override
    public String type() {
        return CompositeCheckType.USER_REGISTER_CHECK.getValue();
    }
}
