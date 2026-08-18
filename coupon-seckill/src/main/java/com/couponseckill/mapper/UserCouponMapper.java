package com.couponseckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couponseckill.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户券 Mapper。对账类 SQL 跨分片，显式传物理表名。
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 批量置过期：status=0 未使用 且 valid_end < now → 已过期(3) */
    @Update("UPDATE ${table} SET status = 3, update_time = NOW() " +
            "WHERE status = 0 AND valid_end < #{now}")
    int expireInShard(@Param("table") String table, @Param("now") String now);

    /** 作废用户多余券（限购对账修复）：id 列表置已作废(4) */
    @Update("UPDATE ${table} SET status = 4, update_time = NOW() " +
            "WHERE id = #{couponId} AND status = 0")
    int invalidateCoupon(@Param("table") String table, @Param("couponId") Long couponId);
}
