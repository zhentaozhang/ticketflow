package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.dto.CreateActivityRequest;
import com.couponseckill.dto.CreateTemplateRequest;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.mapper.CouponTemplateMapper;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端服务：券模板、秒杀活动（创建/发布/下架/调库存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManageService {

    private final CouponTemplateMapper couponTemplateMapper;
    private final FlashSaleActivityMapper activityMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ActivityCacheService cacheService;

    @Transactional(rollbackFor = Exception.class)
    public CouponTemplate createTemplate(CreateTemplateRequest req) {
        CouponTemplate template = new CouponTemplate();
        template.setId(idGenerator.nextId());
        template.setTemplateNo("TPL" + idGenerator.nextIdStr());
        template.setName(req.getName());
        template.setType(req.getType());
        template.setAmount(req.getAmount());
        template.setMinAmount(req.getMinAmount() == null ? java.math.BigDecimal.ZERO : req.getMinAmount());
        template.setValidType(req.getValidType());
        template.setValidStart(req.getValidStart());
        template.setValidEnd(req.getValidEnd());
        template.setValidDays(req.getValidDays());
        template.setScope(req.getScope() == null ? 0 : req.getScope());
        template.setStatus(CouponTemplate.STATUS_ENABLED);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        couponTemplateMapper.insert(template);
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    public FlashSaleActivity createActivity(CreateActivityRequest req) {
        CouponTemplate template = couponTemplateMapper.selectById(req.getCouponTemplateId());
        if (template == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "券模板不存在: " + req.getCouponTemplateId());
        }
        if (!req.getEndTime().isAfter(req.getStartTime())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(idGenerator.nextId());
        activity.setActivityNo("FS" + idGenerator.nextIdStr());
        activity.setCouponTemplateId(req.getCouponTemplateId());
        activity.setActivityName(req.getActivityName());
        activity.setStartTime(req.getStartTime());
        activity.setEndTime(req.getEndTime());
        activity.setTotalStock(req.getTotalStock());
        activity.setStock(req.getTotalStock());
        activity.setPerUserLimit(req.getPerUserLimit());
        activity.setStatus(FlashSaleActivity.STATUS_DRAFT);
        activity.setVersion(0);
        activity.setCreateTime(LocalDateTime.now());
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.insert(activity);
        return activity;
    }

    /**
     * 发布活动：草稿 → 未开始，并预热 Redis（meta + 库存）。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlashSaleActivity publish(Long activityId) {
        FlashSaleActivity activity = requireActivity(activityId);
        if (activity.getStatus() != FlashSaleActivity.STATUS_DRAFT) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅草稿状态活动可发布, 当前状态=" + activity.getStatus());
        }
        activity.setStatus(FlashSaleActivity.STATUS_NOT_STARTED);
        activity.setUpdateTime(LocalDateTime.now());
        int rows = activityMapper.updateById(activity); // 乐观锁 where version
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "发布失败，请重试");
        }
        cacheService.warmUp(activity);
        return activity;
    }

    /**
     * 紧急下架：进行中/未开始 → 已下架，清理 Redis。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlashSaleActivity offline(Long activityId) {
        FlashSaleActivity activity = requireActivity(activityId);
        if (activity.getStatus() == FlashSaleActivity.STATUS_ENDED || activity.getStatus() == FlashSaleActivity.STATUS_OFFLINE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前状态不可下架");
        }
        activity.setStatus(FlashSaleActivity.STATUS_OFFLINE);
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.updateById(activity);
        cacheService.remove(activityId);
        return activity;
    }

    /**
     * 库存调整（仅进行中/未开始允许，如运营追补库存）。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlashSaleActivity adjustStock(Long activityId, int delta) {
        FlashSaleActivity activity = requireActivity(activityId);
        if (activity.getStatus() == FlashSaleActivity.STATUS_ENDED || activity.getStatus() == FlashSaleActivity.STATUS_OFFLINE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "活动已结束/下架，不可调整库存");
        }
        int newStock = activity.getStock() + delta;
        if (newStock < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "调整后库存为负");
        }
        if (newStock > activity.getTotalStock()) {
            // 允许追补：同步更新总库存
            activity.setTotalStock(newStock);
        }
        activity.setStock(newStock);
        activity.setUpdateTime(LocalDateTime.now());
        activityMapper.updateById(activity);
        cacheService.adjustStock(activityId, delta);
        return activity;
    }

    public FlashSaleActivity getActivity(Long activityId) {
        return requireActivity(activityId);
    }

    public List<FlashSaleActivity> listActivities() {
        return activityMapper.selectList(new LambdaQueryWrapper<FlashSaleActivity>()
                .orderByDesc(FlashSaleActivity::getCreateTime));
    }

    private FlashSaleActivity requireActivity(Long activityId) {
        FlashSaleActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }
}
