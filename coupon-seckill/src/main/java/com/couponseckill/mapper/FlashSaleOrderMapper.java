package com.couponseckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.couponseckill.entity.FlashSaleOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 抢购流水 Mapper。
 * 常规 CRUD 用逻辑表名（flash_sale_order，由 ShardingContext 路由分片）；
 * 对账类查询跨分片，显式传物理表名（${table}，值来自 ShardingContext.shardTable，无注入风险）。
 */
public interface FlashSaleOrderMapper extends BaseMapper<FlashSaleOrder> {

    /** 对账：某分片内活动已发券数（status=1 已发券） */
    @Select("SELECT COUNT(1) FROM ${table} WHERE activity_id = #{activityId} AND status = 1")
    long countIssuedInShard(@Param("table") String table, @Param("activityId") Long activityId);

    /** 发券超时扫描：某分片内处理中超时的流水数 */
    @Select("SELECT COUNT(1) FROM ${table} WHERE activity_id = #{activityId} AND status = 0 AND create_time < #{deadline}")
    long countTimeoutInShard(@Param("table") String table, @Param("activityId") Long activityId,
                             @Param("deadline") String deadline);

    /** 限购对账：某分片内发放数超过限购的用户 */
    @Select("SELECT user_id AS userId, COUNT(1) AS cnt FROM ${table} " +
            "WHERE activity_id = #{activityId} AND status = 1 " +
            "GROUP BY user_id HAVING COUNT(1) > #{limit}")
    List<Map<String, Object>> findOverLimitInShard(@Param("table") String table,
                                                   @Param("activityId") Long activityId,
                                                   @Param("limit") int limit);

    /** 发券超时扫描：列出某分片内超时的处理中流水（用于重投/回补） */
    @Select("SELECT * FROM ${table} WHERE status = 0 AND create_time < #{deadline} LIMIT #{max}")
    List<FlashSaleOrder> listTimeoutInShard(@Param("table") String table,
                                            @Param("deadline") String deadline,
                                            @Param("max") int max);
}
