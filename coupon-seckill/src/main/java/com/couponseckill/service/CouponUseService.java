package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.RedisKeys;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.dto.CouponOperateResult;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 用券服务：锁券（Redis SETNX + DB 乐观锁双层）/ 核销 / 退回。
 * 对应 docs/01-技术设计.md §3.3 与 §8.3 —— 用券环节强一致（DB 事务 + 乐观锁）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponUseService {

    private final UserCouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 锁券：下单时调用，防并发使用。
     * 第一道闸 Redis SETNX（带 TTL 防死锁），第二道闸 DB 乐观锁（status=未使用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponOperateResult lockCoupon(String couponNo, Long userId, String orderNo) {
        ShardingContext.setUserId(userId);
        try {
            // 0) 先确认券存在且属于该用户
            UserCoupon coupon = findCoupon(couponNo, userId);

            // 1) Redis 锁（值=orderNo，NX+EX 防并发与死锁）
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    RedisKeys.couponLock(couponNo), orderNo, 30, TimeUnit.MINUTES);
            if (locked == null || !locked) {
                throw new BizException(ErrorCode.COUPON_ALREADY_LOCKED);
            }

            // 2) DB 乐观锁：仅 未使用 → 已锁定
            int rows = couponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                    .set(UserCoupon::getStatus, UserCoupon.STATUS_LOCKED)
                    .set(UserCoupon::getOrderNo, orderNo)
                    .set(UserCoupon::getLockTime, LocalDateTime.now())
                    .set(UserCoupon::getUpdateTime, LocalDateTime.now())
                    .eq(UserCoupon::getId, coupon.getId())
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getStatus, UserCoupon.STATUS_UNUSED));
            if (rows == 0) {
                // 乐观锁失败：释放 Redis 锁（若还属于本订单）
                releaseLockIfOwner(couponNo, orderNo);
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE);
            }
            coupon.setStatus(UserCoupon.STATUS_LOCKED);
            coupon.setOrderNo(orderNo);
            return CouponOperateResult.of(coupon);
        } finally {
            ShardingContext.clear();
        }
    }

    /**
     * 核销：订单支付成功后调用（已锁定 → 已使用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void useCoupon(String couponNo, Long userId, String orderNo) {
        ShardingContext.setUserId(userId);
        try {
            int rows = couponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                    .set(UserCoupon::getStatus, UserCoupon.STATUS_USED)
                    .set(UserCoupon::getUseTime, LocalDateTime.now())
                    .set(UserCoupon::getUpdateTime, LocalDateTime.now())
                    .eq(UserCoupon::getCouponNo, couponNo)
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getOrderNo, orderNo)
                    .eq(UserCoupon::getStatus, UserCoupon.STATUS_LOCKED));
            if (rows == 0) {
                throw new BizException(ErrorCode.COUPON_ORDER_MISMATCH);
            }
            releaseLock(couponNo);
        } finally {
            ShardingContext.clear();
        }
    }

    /**
     * 退回：订单取消/支付超时（已锁定 → 未使用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnCoupon(String couponNo, Long userId, String orderNo) {
        ShardingContext.setUserId(userId);
        try {
            int rows = couponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                    .set(UserCoupon::getStatus, UserCoupon.STATUS_UNUSED)
                    .set(UserCoupon::getOrderNo, null)
                    .set(UserCoupon::getLockTime, null)
                    .set(UserCoupon::getUpdateTime, LocalDateTime.now())
                    .eq(UserCoupon::getCouponNo, couponNo)
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getOrderNo, orderNo)
                    .eq(UserCoupon::getStatus, UserCoupon.STATUS_LOCKED));
            if (rows == 0) {
                throw new BizException(ErrorCode.COUPON_ORDER_MISMATCH);
            }
            releaseLock(couponNo);
        } finally {
            ShardingContext.clear();
        }
    }

    public UserCoupon getCoupon(String couponNo, Long userId) {
        ShardingContext.setUserId(userId);
        try {
            UserCoupon coupon = findCoupon(couponNo, userId);
            return coupon;
        } finally {
            ShardingContext.clear();
        }
    }

    private UserCoupon findCoupon(String couponNo, Long userId) {
        UserCoupon coupon = couponMapper.selectOne(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getCouponNo, couponNo)
                .eq(UserCoupon::getUserId, userId)
                .last("LIMIT 1"));
        if (coupon == null) {
            throw new BizException(ErrorCode.COUPON_NOT_FOUND);
        }
        return coupon;
    }

    private void releaseLock(String couponNo) {
        redisTemplate.delete(RedisKeys.couponLock(couponNo));
    }

    private void releaseLockIfOwner(String couponNo, String orderNo) {
        String owner = redisTemplate.opsForValue().get(RedisKeys.couponLock(couponNo));
        if (orderNo.equals(owner)) {
            redisTemplate.delete(RedisKeys.couponLock(couponNo));
        }
    }
}
