package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.model.common.RepCodeEnum;
import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.service.CaptchaService;
import com.ticketflow.captcha.util.AesUtil;
import com.ticketflow.captcha.util.Md5Util;
import com.ticketflow.captcha.util.StringUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import java.awt.*;
import java.nio.charset.StandardCharsets;

/**
 * 验证码抽象服务类。提供验证码校验、二次校验、限流与字体加载等通用逻辑，具体验证码类型由子类实现。
 **/
public abstract class AbstractCaptchaService implements CaptchaService {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected static final String IMAGE_TYPE_PNG = "png";

    protected static final int HAN_ZI_SIZE = 25;

    protected static final int HAN_ZI_SIZE_HALF = HAN_ZI_SIZE / 2;

    /**
     * check校验坐标
     */
    protected static final String REDIS_CAPTCHA_KEY = "RUNNING:CAPTCHA:%s";

    /**
     * 后台二次校验坐标
     */
    protected static final String REDIS_SECOND_CAPTCHA_KEY = "RUNNING:CAPTCHA:second-%s";

    protected static final long EXPIRE_SIN_SECONDS = 2 * 60L;

    protected static final long EXPIRE_SIN_THREE = 3 * 60L;

    protected static final String TTF = ".ttf";

    protected static final String TTC = ".ttc";

    @Value("${aj.captcha.water-mark:我的水印}")
    protected String waterMark = "我的水印";

    @Value("${aj.captcha.slip-offset:5}")
    protected String slipOffset = "5";

    @Value("${aj.captcha.aes-status:true}")
    protected Boolean captchaAesStatus = true;

    @Value("${aj.captcha.interference-options:0}")
    protected int captchaInterferenceOptions = 0;

    @Value("${aj.captcha.water-font:WenQuanZhengHei.ttf}")
    protected String waterMarkFontStr = "WenQuanZhengHei.ttf";

    @Value("${aj.captcha.font-type:WenQuanZhengHei.ttf}")
    protected String clickWordFontStr = "WenQuanZhengHei.ttf";

    @Value("${aj.captcha.req-frequency-limit-enable:false}")
    protected boolean reqFrequencyLimitEnable = false;

    @Value("${aj.captcha.req-get-lock-limit:5}")
    protected int reqGetLockLimit = 5;

    @Value("${aj.captcha.req-get-lock-seconds:300}")
    protected long reqGetLockSeconds = 300L;

    @Value("${aj.captcha.req-get-minute-limit:120}")
    protected int reqGetMinuteLimit = 120;

    @Value("${aj.captcha.req-check-minute-limit:600}")
    protected int reqCheckMinuteLimit = 600;

    @Value("${aj.captcha.req-verify-minute-limit:600}")
    protected int reqVerifyMinuteLimit = 600;

    @Autowired
    @Qualifier("AjCaptchaCacheService")
    protected CaptchaCacheService cacheService;

    /**
     * 水印字体
     */
    protected Font waterMarkFont;

    private FrequencyLimitHandler limitHandler;

    @PostConstruct
    public void initConfig() {
        loadWaterMarkFont();
        if (reqFrequencyLimitEnable) {
            logger.info("接口分钟内限流开关...开启...");
            limitHandler = new FrequencyLimitHandler.DefaultLimitHandler(cacheService, reqGetMinuteLimit,
                    reqGetLockLimit, reqGetLockSeconds, reqCheckMinuteLimit, reqVerifyMinuteLimit);
        }
    }

    @Override
    public ResponseModel get(CaptchaVO captchaVO) {
        if (limitHandler != null) {
            captchaVO.setClientUid(getValidateClientId(captchaVO));
            return limitHandler.validateGet(captchaVO);
        }
        return null;
    }

    @Override
    public ResponseModel check(CaptchaVO captchaVO) {
        if (limitHandler != null) {
            captchaVO.setClientUid(getValidateClientId(captchaVO));
            return limitHandler.validateCheck(captchaVO);
        }
        return null;
    }

    @Override
    public ResponseModel verification(CaptchaVO captchaVO) {
        if (captchaVO == null) {
            return RepCodeEnum.NULL_ERROR.parseError("captchaVO");
        }
        if (StringUtils.isEmpty(captchaVO.getCaptchaVerification())) {
            return RepCodeEnum.NULL_ERROR.parseError("captchaVerification");
        }
        if (limitHandler != null) {
            ResponseModel limitResp = limitHandler.validateVerify(captchaVO);
            if (!validatedReq(limitResp)) {
                return limitResp;
            }
        }
        try {
            String codeKey = String.format(REDIS_SECOND_CAPTCHA_KEY, captchaVO.getCaptchaVerification());
            if (!cacheService.exists(codeKey)) {
                return ResponseModel.errorMsg(RepCodeEnum.API_CAPTCHA_INVALID);
            }
            //二次校验取值后，即刻失效
            cacheService.delete(codeKey);
        } catch (Exception e) {
            logger.error("验证码坐标解析失败", e);
            return ResponseModel.errorMsg(e.getMessage());
        }
        return ResponseModel.success();
    }

    protected boolean validatedReq(ResponseModel resp) {
        return resp == null || resp.isSuccess();
    }

    protected static String decrypt(String pointJson, String key) throws Exception {
        return AesUtil.aesDecrypt(pointJson, key);
    }

    protected String getValidateClientId(CaptchaVO req) {
        // 以服务端获取的客户端标识 做识别标志
        if (StringUtils.isNotEmpty(req.getBrowserInfo())) {
            return Md5Util.md5(req.getBrowserInfo());
        }
        // 以客户端Ui组件id做识别标志
        if (StringUtils.isNotEmpty(req.getClientUid())) {
            return req.getClientUid();
        }
        return null;
    }

    protected void afterValidateFail(CaptchaVO data) {
        if (limitHandler != null) {
            // 验证失败 分钟内计数
            String fails = String.format(FrequencyLimitHandler.LIMIT_KEY, "FAIL", data.getClientUid());
            if (!cacheService.exists(fails)) {
                cacheService.set(fails, "1", 60);
            }
            cacheService.increment(fails, 1);
        }
    }

    /**
     * 加载resources下的font字体；加载失败时回退系统默认字体
     */
    private void loadWaterMarkFont() {
        try {
            if (waterMarkFontStr.toLowerCase().endsWith(TTF) || waterMarkFontStr.toLowerCase().endsWith(TTC)
                    || waterMarkFontStr.toLowerCase().endsWith(".otf")) {
                this.waterMarkFont = Font.createFont(Font.TRUETYPE_FONT,
                        getClass().getResourceAsStream("/fonts/" + waterMarkFontStr))
                        .deriveFont(Font.BOLD, HAN_ZI_SIZE / 2);
            } else {
                this.waterMarkFont = new Font(waterMarkFontStr, Font.BOLD, HAN_ZI_SIZE / 2);
            }
        } catch (Exception e) {
            logger.warn("load font fail: {}, use default font", waterMarkFontStr);
            this.waterMarkFont = new Font(Font.SANS_SERIF, Font.BOLD, HAN_ZI_SIZE / 2);
        }
    }

    protected static int getEnOrChLength(String s) {
        int enCount = 0;
        int chCount = 0;
        for (int i = 0; i < s.length(); i++) {
            int length = String.valueOf(s.charAt(i)).getBytes(StandardCharsets.UTF_8).length;
            if (length > 1) {
                chCount++;
            } else {
                enCount++;
            }
        }
        int chOffset = (HAN_ZI_SIZE / 2) * chCount + 5;
        int enOffset = enCount * 8;
        return chOffset + enOffset;
    }
}
