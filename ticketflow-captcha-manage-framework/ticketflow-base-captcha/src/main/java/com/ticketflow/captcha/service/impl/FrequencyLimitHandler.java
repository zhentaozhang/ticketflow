package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.model.common.RepCodeEnum;
import com.ticketflow.captcha.model.common.ResponseModel;
import com.ticketflow.captcha.model.vo.CaptchaVO;
import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.util.StringUtils;

import java.util.Objects;

/**
 * 验证码频率限制处理器。根据IP、UUID等维度对验证码请求进行限流，防止暴力破解。
 **/
public interface FrequencyLimitHandler {

    String LIMIT_KEY = "AJ.CAPTCHA.REQ.LIMIT-%s-%s";

    String ONE_HUNDRED_AND_TWENTY = "120";

    String FIVE = "5";

    String SIX_HUNDRED = "600";

    /**
     * get 接口限流
     *
     * @param captchaVO
     * @return
     */
    ResponseModel validateGet(CaptchaVO captchaVO);

    /**
     * check接口限流
     *
     * @param captchaVO
     * @return
     */
    ResponseModel validateCheck(CaptchaVO captchaVO);

    /**
     * verify接口限流
     *
     * @param captchaVO
     * @return
     */
    ResponseModel validateVerify(CaptchaVO captchaVO);

    /***
     * 验证码接口限流:
     *      客户端ClientUid 组件实例化时设置一次，如：场景码+UUID，客户端可以本地缓存,保证一个组件只有一个值
     *
     * 针对同一个客户端的请求，做如下限制:
     * get
     * 	 1分钟内check失败5次，锁定5分钟
     * 	 1分钟内不能超过120次。
     * check:
     *   1分钟内不超过600次
     * verify:
     *   1分钟内不超过600次
     */
    class DefaultLimitHandler implements FrequencyLimitHandler {
        private final CaptchaCacheService cacheService;
        private final int getMinuteLimit;
        private final int getLockLimit;
        private final long getLockSeconds;
        private final int checkMinuteLimit;
        private final int verifyMinuteLimit;

        public DefaultLimitHandler(CaptchaCacheService cacheService, int getMinuteLimit, int getLockLimit,
                                   long getLockSeconds, int checkMinuteLimit, int verifyMinuteLimit) {
            this.cacheService = cacheService;
            this.getMinuteLimit = getMinuteLimit;
            this.getLockLimit = getLockLimit;
            this.getLockSeconds = getLockSeconds;
            this.checkMinuteLimit = checkMinuteLimit;
            this.verifyMinuteLimit = verifyMinuteLimit;
        }

        private String getClientCid(CaptchaVO input, String type) {
            return String.format(LIMIT_KEY, type, input.getClientUid());
        }

        @Override
        public ResponseModel validateGet(CaptchaVO d) {
            // 无客户端身份标识，不限制
            if (StringUtils.isEmpty(d.getClientUid())) {
                return null;
            }
            String getKey = getClientCid(d, "GET");
            String lockKey = getClientCid(d, "LOCK");
            // 失败次数过多，锁定
            if (Objects.nonNull(cacheService.get(lockKey))) {
                return ResponseModel.errorMsg(RepCodeEnum.API_REQ_LOCK_GET_ERROR);
            }
            String gets = cacheService.get(getKey);
            if (Objects.isNull(gets)) {
                cacheService.set(getKey, "1", 60L);
                gets = "1";
            }
            cacheService.increment(getKey, 1L);
            // 1分钟内请求次数过多
            if (Long.parseLong(gets) > getMinuteLimit) {
                return ResponseModel.errorMsg(RepCodeEnum.API_REQ_LIMIT_GET_ERROR);
            }

            // 失败次数验证
            String failKey = getClientCid(d, "FAIL");
            String failCnts = cacheService.get(failKey);
            // 没有验证失败，通过校验
            if (Objects.isNull(failCnts)) {
                return null;
            }
            // 1分钟内失败5次
            if (Long.parseLong(failCnts) > getLockLimit) {
                // get接口锁定5分钟
                cacheService.set(lockKey, "1", getLockSeconds);
                return ResponseModel.errorMsg(RepCodeEnum.API_REQ_LOCK_GET_ERROR);
            }
            return null;
        }

        @Override
        public ResponseModel validateCheck(CaptchaVO d) {
            // 无客户端身份标识，不限制
            if (StringUtils.isEmpty(d.getClientUid())) {
                return null;
            }
            String key = getClientCid(d, "CHECK");
            String v = cacheService.get(key);
            if (Objects.isNull(v)) {
                cacheService.set(key, "1", 60);
                v = "1";
            }
            cacheService.increment(key, 1);
            if (Long.parseLong(v) > checkMinuteLimit) {
                return ResponseModel.errorMsg(RepCodeEnum.API_REQ_LIMIT_CHECK_ERROR);
            }
            return null;
        }

        @Override
        public ResponseModel validateVerify(CaptchaVO d) {
            String key = getClientCid(d, "VERIFY");
            String v = cacheService.get(key);
            if (Objects.isNull(v)) {
                cacheService.set(key, "1", 60);
                v = "1";
            }
            cacheService.increment(key, 1);
            if (Long.parseLong(v) > verifyMinuteLimit) {
                return ResponseModel.errorMsg(RepCodeEnum.API_REQ_LIMIT_VERIFY_ERROR);
            }
            return null;
        }
    }

}
