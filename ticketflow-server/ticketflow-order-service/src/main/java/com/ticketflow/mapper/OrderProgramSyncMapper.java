package com.ticketflow.mapper;

import com.ticketflow.domain.OrderProgramSyncRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 节目数据同步 outbox（复用 d_order 的 program_sync_* 列）。
 * <p>
 * 所有语句都带 {@code order_number} 条件走分片路由：入队与订单状态 CAS 落在同一张物理分表，
 * 才能和订单状态变更待在同一个本地事务里。
 */
public interface OrderProgramSyncMapper {

    /**
     * 入队：把订单行标记为"待同步"并写入 payload。
     * 与订单状态 CAS 在同一个本地事务中执行，所以"状态改了但没入队"不会发生。
     */
    @Update("UPDATE d_order SET program_sync_status = 1, "
            + "program_sync_attempts = 0, "
            + "program_sync_next_time = #{nextTime}, "
            + "program_sync_payload = #{payload}, "
            + "program_sync_error = NULL, "
            + "edit_time = NOW() "
            + "WHERE order_number = #{orderNumber} AND status = 1")
    int enqueue(@Param("orderNumber") Long orderNumber,
                @Param("nextTime") Date nextTime,
                @Param("payload") String payload);

    /**
     * 扫描到期的待同步记录。
     * 没有分片键 → 16 张物理表广播 + 归并排序，所以必须靠 limit 和 idx_program_sync 控制成本。
     */
    @Select("SELECT order_number AS orderNumber, "
            + "program_sync_attempts AS attempts, "
            + "program_sync_payload AS payload "
            + "FROM d_order "
            + "WHERE program_sync_status = 1 AND status = 1 "
            + "AND program_sync_next_time <= #{now} "
            + "ORDER BY program_sync_next_time ASC "
            + "LIMIT #{limit}")
    List<OrderProgramSyncRecord> selectDue(@Param("now") Date now, @Param("limit") int limit);

    /**
     * 已同步。保留 payload 便于事后追查"这条到底推了什么"，不再扫它。
     */
    @Update("UPDATE d_order SET program_sync_status = 2, program_sync_error = NULL, edit_time = NOW() "
            + "WHERE order_number = #{orderNumber} AND status = 1")
    int markDone(@Param("orderNumber") Long orderNumber);

    /**
     * 排下一轮重试。attempts 是累计已尝试次数，next_time 由退避算法算出。
     */
    @Update("UPDATE d_order SET program_sync_status = 1, "
            + "program_sync_attempts = #{attempts}, "
            + "program_sync_next_time = #{nextTime}, "
            + "program_sync_error = #{error}, "
            + "edit_time = NOW() "
            + "WHERE order_number = #{orderNumber} AND status = 1")
    int markRetry(@Param("orderNumber") Long orderNumber,
                  @Param("attempts") Integer attempts,
                  @Param("nextTime") Date nextTime,
                  @Param("error") String error);

    /**
     * 死信：重试次数用尽，不再自动推进，等人工按 payload 处理。
     */
    @Update("UPDATE d_order SET program_sync_status = 3, "
            + "program_sync_attempts = #{attempts}, "
            + "program_sync_error = #{error}, "
            + "edit_time = NOW() "
            + "WHERE order_number = #{orderNumber} AND status = 1")
    int markDead(@Param("orderNumber") Long orderNumber,
                 @Param("attempts") Integer attempts,
                 @Param("error") String error);

    /**
     * 差异扫描：终态订单（已支付/已取消）却没有走过同步登记的 V4 订单。
     * 正常链路里这类行不应该存在，出现即说明入队环节漏了，需要人工按订单核实节目服务 DB。
     */
    @Select("SELECT order_number AS orderNumber FROM d_order "
            + "WHERE program_sync_status = 0 AND status = 1 "
            + "AND order_status IN (2, 3) "
            + "AND order_version = #{orderVersion} "
            + "AND edit_time >= #{from} AND edit_time <= #{to} "
            + "LIMIT #{limit}")
    List<Long> selectTerminalWithoutSync(@Param("orderVersion") Integer orderVersion,
                                        @Param("from") Date from,
                                        @Param("to") Date to,
                                        @Param("limit") int limit);
}
