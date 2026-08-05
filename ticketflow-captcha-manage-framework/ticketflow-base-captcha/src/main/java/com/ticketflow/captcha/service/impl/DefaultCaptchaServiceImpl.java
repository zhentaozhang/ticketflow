package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.model.common.RepCodeEnum;
import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.ticketflow.captcha.service.CaptchaService;
import com.ticketflow.captcha.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认验证码实现。根据请求中的验证码类型委托给对应的验证码服务，作为统一入口。
 **/
@Component("captchaDefault")
public class DefaultCaptchaServiceImpl extends AbstractCaptchaService {

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public String captchaType() {
        return "default";
    }

    private CaptchaService getService(String captchaType) {
        Map<String, CaptchaService> services = applicationContext.getBeansOfType(CaptchaService.class);
        for (CaptchaService service : services.values()) {
            if (service != this && captchaType.equals(service.captchaType())) {
                return service;
            }
        }
        return null;
    }

    @Override
    public ResponseModel get(CaptchaVO captchaVO) {
        if (captchaVO == null) {
            return RepCodeEnum.NULL_ERROR.parseError("captchaVO");
        }
        if (StringUtils.isEmpty(captchaVO.getCaptchaType())) {
            return RepCodeEnum.NULL_ERROR.parseError("类型");
        }
        CaptchaService service = getService(captchaVO.getCaptchaType());
        if (service == null) {
            return RepCodeEnum.PARAM_FORMAT_ERROR.parseError("captchaType");
        }
        return service.get(captchaVO);
    }

    @Override
    public ResponseModel check(CaptchaVO captchaVO) {
        if (captchaVO == null) {
            return RepCodeEnum.NULL_ERROR.parseError("captchaVO");
        }
        if (StringUtils.isEmpty(captchaVO.getCaptchaType())) {
            return RepCodeEnum.NULL_ERROR.parseError("类型");
        }
        if (StringUtils.isEmpty(captchaVO.getToken())) {
            return RepCodeEnum.NULL_ERROR.parseError("token");
        }
        CaptchaService service = getService(captchaVO.getCaptchaType());
        if (service == null) {
            return RepCodeEnum.PARAM_FORMAT_ERROR.parseError("captchaType");
        }
        return service.check(captchaVO);
    }
}
