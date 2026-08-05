package com.ticketflow.captcha.service;

import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;

/**
 * 验证码服务接口。定义验证码的获取、校验和验证接口。
 **/
public interface CaptchaService {

    /**
     * 获取验证码
     *
     * @param captchaVO 数据
     * @return 结果
     */
    ResponseModel get(CaptchaVO captchaVO);

    /**
     * 核对验证码(前端)
     *
     * @param captchaVO 数据
     * @return 结果
     */
    ResponseModel check(CaptchaVO captchaVO);

    /**
     * 二次校验验证码(后端)
     *
     * @param captchaVO 数据
     * @return 结果
     */
    ResponseModel verification(CaptchaVO captchaVO);

    /**
     * 验证码类型(blockPuzzle/clickWord/default)，作为Spring bean名注册
     *
     * @return 结果
     */
    String captchaType();
}
