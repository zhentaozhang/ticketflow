package com.couponseckill.kafka;

import com.couponseckill.entity.FlashSaleOrder;
import lombok.Data;

/**
 * 抢购成功后投递的"发券请求"消息。
 * Kafka key = userId（同一用户串行，简化限购与发券的本地一致性）。
 */
@Data
public class FlashSaleRequestMessage {

    private Long activityId;

    private Long userId;

    private String requestId;

    /** 抢购流水号（业务幂等） */
    private String orderNo;

    /** 抢购成功时间戳（毫秒） */
    private Long grabTime;

    public static FlashSaleRequestMessage of(Long activityId, Long userId, String requestId,
                                             String orderNo, Long grabTime) {
        FlashSaleRequestMessage m = new FlashSaleRequestMessage();
        m.activityId = activityId;
        m.userId = userId;
        m.requestId = requestId;
        m.orderNo = orderNo;
        m.grabTime = grabTime;
        return m;
    }
}
