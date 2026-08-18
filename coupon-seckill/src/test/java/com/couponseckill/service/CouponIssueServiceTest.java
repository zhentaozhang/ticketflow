package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
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
import com.couponseckill.support.MpTableInit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发券服务单元测试：幂等三分支（已存在 / 唯一索引冲突 / 正常）与活动校验。
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    @Mock
    private FlashSaleOrderMapper orderMapper;
    @Mock
    private UserCouponMapper couponMapper;
    @Mock
    private FlashSaleActivityMapper activityMapper;
    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private FlashSaleResultService resultService;

    @InjectMocks
    private CouponIssueService issueService;

    private FlashSaleRequestMessage message;

    @BeforeAll
    static void initMp() {
        MpTableInit.init();
    }

    @BeforeEach
    void setUp() {
        message = new FlashSaleRequestMessage();
        message.setActivityId(1L);
        message.setUserId(100L);
        message.setRequestId("req-1");
        message.setOrderNo("FS-1");
        message.setGrabTime(System.currentTimeMillis());
    }

    private FlashSaleActivity activity() {
        FlashSaleActivity a = new FlashSaleActivity();
        a.setId(1L);
        a.setCouponTemplateId(9L);
        return a;
    }

    private CouponTemplate template() {
        CouponTemplate t = new CouponTemplate();
        t.setId(9L);
        t.setType(CouponTemplate.TYPE_FULL_REDUCTION);
        t.setAmount(new BigDecimal("20.00"));
        t.setMinAmount(new BigDecimal("100.00"));
        t.setValidType(CouponTemplate.VALID_TYPE_DAYS);
        t.setValidDays(30);
        return t;
    }

    @Test
    @DisplayName("幂等：同 (activityId,userId,requestId) 已有流水 → 直接返回不再发券")
    void existingOrderIgnored() {
        FlashSaleOrder existing = new FlashSaleOrder();
        existing.setStatus(FlashSaleOrder.STATUS_ISSUED);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        issueService.issue(message);

        verify(orderMapper, never()).insert(any(FlashSaleOrder.class));
        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    @DisplayName("幂等：并发插入唯一索引冲突 → 视为已处理")
    void duplicateKeyConflictIgnored() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderMapper.insert(any(FlashSaleOrder.class))).thenThrow(new DuplicateKeyException("dup"));

        issueService.issue(message);

        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    @DisplayName("活动不存在 → 抛业务异常（触发消费重试/死信）")
    void missingActivityFails() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(activityMapper.selectById(1L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> issueService.issue(message));
        assertEquals(ErrorCode.ACTIVITY_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("正常发券：流水+券落库，回写结果")
    void issueSuccess() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderMapper.insert(any(FlashSaleOrder.class))).thenReturn(1);
        when(activityMapper.selectById(1L)).thenReturn(activity());
        when(templateMapper.selectById(9L)).thenReturn(template());
        when(idGenerator.nextId()).thenReturn(100L, 200L);

        issueService.issue(message);

        verify(couponMapper).insert(any(UserCoupon.class));
        verify(orderMapper).updateById(any(FlashSaleOrder.class));
        verify(resultService).writeSuccess(anyLong(), anyLong(), any(UserCoupon.class));
    }

    @Test
    @DisplayName("发券面额/有效期按模板快照")
    void couponSnapshotFromTemplate() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderMapper.insert(any(FlashSaleOrder.class))).thenReturn(1);
        when(activityMapper.selectById(1L)).thenReturn(activity());
        when(templateMapper.selectById(9L)).thenReturn(template());
        when(idGenerator.nextId()).thenReturn(100L, 200L);

        issueService.issue(message);

        var captor = org.mockito.ArgumentCaptor.forClass(UserCoupon.class);
        verify(couponMapper).insert(captor.capture());
        UserCoupon coupon = captor.getValue();
        assertEquals(0, new BigDecimal("20.00").compareTo(coupon.getAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(coupon.getMinAmount()));
        assertEquals(UserCoupon.STATUS_UNUSED, coupon.getStatus());
        assertTrue(coupon.getValidEnd().isAfter(coupon.getValidStart()));
    }
}
