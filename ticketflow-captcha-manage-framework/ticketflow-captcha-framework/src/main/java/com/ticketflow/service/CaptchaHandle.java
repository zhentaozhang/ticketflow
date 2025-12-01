package com.ticketflow.service;

import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.ticketflow.captcha.service.CaptchaService;
import com.ticketflow.util.RemoteUtil;
import lombok.AllArgsConstructor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 验证码处理器。封装验证码生成和校验逻辑，提取客户端IP并委托给验证码服务。
 **/
@AllArgsConstructor
public class CaptchaHandle {

    private final CaptchaService captchaService;

    public ResponseModel getCaptcha(CaptchaVO captchaVO) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        assert requestAttributes != null;
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        captchaVO.setBrowserInfo(RemoteUtil.getRemoteId(request));
        return captchaService.get(captchaVO);
    }

    public ResponseModel checkCaptcha(CaptchaVO captchaVO) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        assert requestAttributes != null;
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        captchaVO.setBrowserInfo(RemoteUtil.getRemoteId(request));
        return captchaService.check(captchaVO);
    }

    public ResponseModel verification(CaptchaVO captchaVO) {
        return captchaService.verification(captchaVO);
    }
}
