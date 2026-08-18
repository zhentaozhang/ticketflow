package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.entity.FlashSaleOrder;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.kafka.FlashSaleRequestMessage;
import com.couponseckill.mapper.CouponTemplateMapper;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import com.couponseckill.mapper.FlashSaleOrderMapper;
import com.couponseckill.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 发券服务：消费端落库（幂等 + 事务 + 结果回写）。
 * 对应 docs/01-技术设计.md §7.3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final FlashSaleOrderMapper orderMapper;
    private final UserCouponMapper couponMapper;
    private final FlashSaleActivityMapper activityMapper;
    private final CouponTemplateMapper templateMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;
    private final FlashSaleResultService resultService;

    /**
     * 发券（消息消费入口）。幂等由 uk_activity_user_request 唯一索引保证：
     * 重复消费 → select 命中直接返回；并发冲突 → DuplicateKeyException 视为已处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public void issue(FlashSaleRequestMessage msg) {
        ShardingContext.setUserId(msg.getUserId());
        try {
            // 1) 幂等：同 (activityId, userId, requestId) 已有流水则已处理
            FlashSaleOrder existing = orderMapper.selectOne(new LambdaQueryWrapper<FlashSaleOrder>()
                    .eq(FlashSaleOrder::getActivityId, msg.getActivityId())
                    .eq(FlashSaleOrder::getUserId, msg.getUserId())
                    .eq(FlashSaleOrder::getRequestId, msg.getRequestId())
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("[issue-dup-ignored] activityId={} userId={} requestId={} status={}",
                        msg.getActivityId(), msg.getUserId(), msg.getRequestId(), existing.getStatus());
                return;
            }

            // 2) 插入抢购流水（处理中）；唯一索引冲突 = 并发重复，视为已处理
            FlashSaleOrder order = new FlashSaleOrder();
            order.setId(idGenerator.nextId());
            order.setOrderNo(msg.getOrderNo());
            order.setActivityId(msg.getActivityId());
            order.setUserId(msg.getUserId());
            order.setRequestId(msg.getRequestId());
            order.setStatus(FlashSaleOrder.STATUS_PROCESSING);
            order.setRetryCount(0);
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException e) {
                log.info("[issue-dup-conflict] activityId={} userId={} requestId={} ignored",
                        msg.getActivityId(), msg.getUserId(), msg.getRequestId());
                return;
            }

            // 3) 生成用户券（券面额/有效期从模板快照）
            FlashSaleActivity activity = activityMapper.selectById(msg.getActivityId());
            if (activity == null) {
                throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND, "活动不存在: " + msg.getActivityId());
            }
            CouponTemplate template = templateMapper.selectById(activity.getCouponTemplateId());
            if (template == null) {
                throw new BizException(ErrorCode.COUPON_ISSUE_FAILED, "券模板不存在: " + activity.getCouponTemplateId());
            }

            UserCoupon coupon = buildCoupon(template, activity, msg.getUserId());
            couponMapper.insert(coupon);

            // 4) 流水置已发券 + 回填 couponId
            order.setCouponId(coupon.getId());
            order.setStatus(FlashSaleOrder.STATUS_ISSUED);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            // 5) 回写 Redis 抢购结果（失败可容忍：查询走 DB 兜底）
            resultService.writeSuccess(msg.getUserId(), msg.getActivityId(), coupon);
            log.info("[issue-ok] activityId={} userId={} couponNo={} orderNo={}",
                    msg.getActivityId(), msg.getUserId(), coupon.getCouponNo(), order.getOrderNo());
        } finally {
            ShardingContext.clear();
        }
    }

    private UserCoupon buildCoupon(CouponTemplate template, FlashSaleActivity activity, Long userId) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(idGenerator.nextId());
        coupon.setCouponNo("CP" + idGenerator.nextIdStr());
        coupon.setUserId(userId);
        coupon.setActivityId(activity.getId());
        coupon.setTemplateId(template.getId());
        coupon.setAmount(template.getAmount());
        coupon.setMinAmount(template.getMinAmount() == null ? BigDecimal.ZERO : template.getMinAmount());
        LocalDateTime now = LocalDateTime.now();
        if (template.getValidType() == CouponTemplate.VALID_TYPE_DAYS) {
            int days = template.getValidDays() == null ? 30 : template.getValidDays();
            coupon.setValidStart(now);
            coupon.setValidEnd(now.plusDays(days));
        } else {
            coupon.setValidStart(template.getValidStart());
            coupon.setValidEnd(template.getValidEnd());
        }
        coupon.setStatus(UserCoupon.STATUS_UNUSED);
        coupon.setCreateTime(now);
        coupon.setUpdateTime(now);
        return coupon;
    }
}
