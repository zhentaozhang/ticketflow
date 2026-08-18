package com.couponseckill.service;

import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.dto.CreateActivityRequest;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.mapper.CouponTemplateMapper;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理服务单元测试：校验规则与状态流转。
 */
@ExtendWith(MockitoExtension.class)
class ManageServiceTest {

    @Mock
    private CouponTemplateMapper couponTemplateMapper;
    @Mock
    private FlashSaleActivityMapper activityMapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private ActivityCacheService cacheService;

    @InjectMocks
    private ManageService manageService;

    private CreateActivityRequest request(Long templateId, LocalDateTime start, LocalDateTime end) {
        CreateActivityRequest req = new CreateActivityRequest();
        req.setCouponTemplateId(templateId);
        req.setActivityName("测试活动");
        req.setStartTime(start);
        req.setEndTime(end);
        req.setTotalStock(100);
        req.setPerUserLimit(1);
        return req;
    }

    @Test
    @DisplayName("创建活动：结束时间早于开始时间被拒绝")
    void createActivityRejectsInvertedWindow() {
        LocalDateTime now = LocalDateTime.now();
        CreateActivityRequest req = request(1L, now.plusHours(2), now.plusHours(1));
        BizException e = assertThrows(BizException.class, () -> manageService.createActivity(req));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("创建活动：券模板不存在被拒绝")
    void createActivityRejectsMissingTemplate() {
        when(couponTemplateMapper.selectById(99L)).thenReturn(null);
        LocalDateTime now = LocalDateTime.now();
        BizException e = assertThrows(BizException.class,
                () -> manageService.createActivity(request(99L, now, now.plusHours(1))));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("发布：仅草稿状态可发布")
    void publishOnlyFromDraft() {
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(1L);
        activity.setStatus(FlashSaleActivity.STATUS_ONGOING);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        BizException e = assertThrows(BizException.class, () -> manageService.publish(1L));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(cacheService, never()).warmUp(any());
    }

    @Test
    @DisplayName("发布：乐观锁冲突（行数=0）返回系统繁忙")
    void publishOptimisticLockConflict() {
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(1L);
        activity.setStatus(FlashSaleActivity.STATUS_DRAFT);
        activity.setVersion(0);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(activityMapper.updateById(activity)).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> manageService.publish(1L));
        assertEquals(ErrorCode.SYSTEM_BUSY, e.getErrorCode());
        verify(cacheService, never()).warmUp(any());
    }

    @Test
    @DisplayName("发布：成功后预热 Redis")
    void publishWarmsUpCache() {
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(1L);
        activity.setStatus(FlashSaleActivity.STATUS_DRAFT);
        activity.setVersion(0);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(activityMapper.updateById(activity)).thenReturn(1);

        manageService.publish(1L);
        assertEquals(FlashSaleActivity.STATUS_NOT_STARTED, activity.getStatus());
        verify(cacheService).warmUp(activity);
    }

    @Test
    @DisplayName("下架：已下架活动不可重复下架")
    void offlineOnlyActive() {
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(1L);
        activity.setStatus(FlashSaleActivity.STATUS_OFFLINE);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        assertThrows(BizException.class, () -> manageService.offline(1L));
        verify(cacheService, never()).remove(any());
    }

    @Test
    @DisplayName("调库存：调整后为负被拒绝")
    void adjustStockNegativeRejected() {
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setId(1L);
        activity.setStatus(FlashSaleActivity.STATUS_ONGOING);
        activity.setStock(10);
        activity.setTotalStock(10);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        BizException e = assertThrows(BizException.class, () -> manageService.adjustStock(1L, -11));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }
}
