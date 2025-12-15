package com.ticketflow.service;

import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.lua.CheckNeedCaptchaOperate;
import com.ticketflow.vo.CheckNeedCaptchaDataVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证码判断服务——根据用户操作频率决定是否弹出滑块验证码。
 * <p>
 * 基于 Lua 脚本（CheckNeedCaptchaOperate）在 Redis 中统计
 * 单位时间内的操作次数，超过阈值时要求验证码
 */
@Service
public class UserCaptchaService {

    @Value("${verify_captcha_threshold:10}")
    private int verifyCaptchaThreshold;

    @Value("${verify_captcha_id_expire_time:60}")
    private int verifyCaptchaIdExpireTime;

    @Value("${always_verify_captcha:0}")
    private int alwaysVerifyCaptcha;

    @Autowired
    private CaptchaHandle captchaHandle;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private CheckNeedCaptchaOperate checkNeedCaptchaOperate;
    public CheckNeedCaptchaDataVo checkNeedCaptcha() {
        long currentTimeMillis = System.currentTimeMillis();
        long id = uidGenerator.getUid();
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.COUNTER_COUNT).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.COUNTER_TIMESTAMP).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.VERIFY_CAPTCHA_ID, id).getRelKey());
        String[] data = new String[4];
        data[0] = String.valueOf(verifyCaptchaThreshold);
        data[1] = String.valueOf(currentTimeMillis);
        data[2] = String.valueOf(verifyCaptchaIdExpireTime);
        data[3] = String.valueOf(alwaysVerifyCaptcha);
        Boolean result = checkNeedCaptchaOperate.checkNeedCaptchaOperate(keys, data);
        CheckNeedCaptchaDataVo checkNeedCaptchaDataVo = new CheckNeedCaptchaDataVo();
        checkNeedCaptchaDataVo.setCaptchaId(id);
        checkNeedCaptchaDataVo.setVerifyCaptcha(result);
        return checkNeedCaptchaDataVo;
    }

    public ResponseModel getCaptcha(CaptchaVO captchaVO) {
        return captchaHandle.getCaptcha(captchaVO);
    }

    public ResponseModel verifyCaptcha(final CaptchaVO captchaVO) {
        return captchaHandle.checkCaptcha(captchaVO);
    }
}
