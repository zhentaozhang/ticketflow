package com.ticketflow.domain;

import com.ticketflow.dto.TicketCategoryCountDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节目数据同步 payload（订单服务 → 节目服务）。
 * <p>
 * <b>只由订单行（DB）构造，不读 Redis</b>：它的构造发生在订单状态 CAS 的同一个本地事务里，
 * 如果构造过程依赖 Redis，Redis 抖动就会把已经成功的支付/取消一起回滚掉。
 * <p>
 * 一条 payload 同时承担两件事：
 * <ul>
 *   <li>重放给节目服务（转成 {@code ProgramOperateDataDto}）；</li>
 *   <li>校验 Redis（权威源）是否已经收敛（{@link #ticketCategorySeatMap} 是 Redis 收敛校验的输入）。</li>
 * </ul>
 */
@Data
public class ProgramSyncPayload {

    /** 节目 id */
    private Long programId;

    /** 订单号（d_order.order_number，用来定位 outbox 行） */
    private Long orderNumber;

    /** 节目服务侧的业务标识（ProgramOperateDataDto.orderNumber），与订单创建时保持一致 */
    private Long identifierId;

    /** 目标售卖状态：{@code SellStatus.SOLD}（支付）/ {@code SellStatus.NO_SOLD}（取消） */
    private Integer sellStatus;

    /** 创建订单时的版本（ProgramOrderVersion） */
    private Integer orderVersion;

    /** 票档维度的座位数量（节目服务据此扣减/归还余量） */
    private List<TicketCategoryCountDto> ticketCategoryCountDtoList;

    /** 票档 id → 座位 id 集合（Redis 收敛校验与 DTO 的 seatIdList 都从这里来） */
    private Map<Long, List<Long>> ticketCategorySeatMap;

    /**
     * 由订单行数据构造 payload。
     *
     * @param seatMap 票档 id → 座位 id 集合（来自 d_order_ticket_user）
     */
    public static ProgramSyncPayload of(Long programId,
                                        Long orderNumber,
                                        Long identifierId,
                                        Integer sellStatus,
                                        Integer orderVersion,
                                        Map<Long, List<Long>> seatMap) {
        ProgramSyncPayload payload = new ProgramSyncPayload();
        payload.setProgramId(programId);
        payload.setOrderNumber(orderNumber);
        payload.setIdentifierId(identifierId);
        payload.setSellStatus(sellStatus);
        payload.setOrderVersion(orderVersion);
        List<TicketCategoryCountDto> ticketCategoryCountDtoList = new ArrayList<>();
        Map<Long, List<Long>> ticketCategorySeatMap = new HashMap<>();
        if (seatMap != null) {
            seatMap.forEach((ticketCategoryId, seatIdList) -> {
                if (ticketCategoryId == null || seatIdList == null || seatIdList.isEmpty()) {
                    return;
                }
                ticketCategoryCountDtoList.add(
                        new TicketCategoryCountDto(ticketCategoryId, (long) seatIdList.size()));
                ticketCategorySeatMap.put(ticketCategoryId, new ArrayList<>(seatIdList));
            });
        }
        payload.setTicketCategoryCountDtoList(ticketCategoryCountDtoList);
        payload.setTicketCategorySeatMap(ticketCategorySeatMap);
        return payload;
    }

    /** 展开成节目服务要求的座位 id 列表 */
    public List<Long> getAllSeatIdList() {
        List<Long> seatIdList = new ArrayList<>();
        if (ticketCategorySeatMap == null) {
            return seatIdList;
        }
        ticketCategorySeatMap.values().forEach(seatIdList::addAll);
        return seatIdList;
    }
}
