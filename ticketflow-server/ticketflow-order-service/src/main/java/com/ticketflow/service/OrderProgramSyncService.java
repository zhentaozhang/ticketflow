package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.OrderProgramSyncRecord;
import com.ticketflow.domain.ProgramSyncPayload;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.SeatSellStatusDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.mapper.OrderProgramSyncMapper;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.SeatSellStatusVo;
import com.ticketflow.vo.SeatVo;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节目数据同步（支付/取消后把节目服务 DB 收敛到目标状态）。
 * <p>
 * <b>它解决的闭环缺口</b>：支付/取消链路里，Redis（座位三区 + 余票）由 Lua 原子收敛，
 * 但节目服务 DB 是<b>派生数据</b>，只在事务外"尽力而为"地推一次 Feign——失败只打一行日志。
 * 进程重启、网络抖动、节目服务瞬时不可用，都会让 DB 与 Redis 永久分叉，
 * 而"DB 脏"不会自己变干净。这里把这份"必须完成但一定不能拖垮主流程"的工作收进
 * 订单行自己的 outbox（{@code d_order.program_sync_*}），由本地事务保证"状态改了必然入队"，
 * 再由定时任务负责最终送达。
 * <p>
 * <b>三条不可违反的规则</b>（都是在生产实现里踩过的坑）：
 * <ol>
 *   <li><b>绝不重放 Lua。</b> Lua 的取消分支是无条件 HINCRBY 归还余量，重放会双倍还票；
 *       它是"一次性收敛"的操作，只由订单流程触发一次。</li>
 *   <li><b>Redis 先于 DB。</b> Redis 是权威源，它没收敛就绝不允许把 DB 推到前面——
 *       否则 DB 会写进 Redis 尚未承认的状态。未收敛一律退避重试。</li>
 *   <li><b>"失败"要先复核。</b> 推送超时可能只是响应丢了，DB 其实已经改成功。
 *       直接重推会撞上节目服务的 LOCK 守卫（幂等，安全），但会白耗一轮重试和一次告警，
 *       所以先用座位状态查询把"已生效"和"没生效"分开。</li>
 * </ol>
 */
@Slf4j
@Service
public class OrderProgramSyncService {

    /**
     * 最多尝试次数（含首次）。耗尽后转死信转人工——不无限重试，
     * 因为反复失败的通常是"数据本身不对"（座位被篡改、节目已下线），重试解决不了。
     */
    private static final int MAX_ATTEMPTS = 6;

    /**
     * 单轮最多处理多少条。每条都是一次 Feign 调用，必须限量。
     */
    private static final int BATCH_SIZE = 50;

    /**
     * 首次重试间隔（秒），之后按 2 的幂退避，封顶 10 分钟。
     */
    private static final long BASE_BACKOFF_SECONDS = 30L;
    private static final long MAX_BACKOFF_SECONDS = 600L;

    /**
     * 差异扫描的时间窗（分钟）：只看最近这段时间内变成终态的订单。
     * 窗口内更早的历史存量（上线前产生、没有 payload 无法自动重放）留给人工核对，避免任务被历史数据淹掉。
     */
    private static final int UNSYNCED_SCAN_WINDOW_MINUTES = 120;

    /**
     * 失败原因列宽（program_sync_error varchar(500)）。
     */
    private static final int ERROR_MAX_LENGTH = 500;

    @Autowired
    private OrderProgramSyncMapper orderProgramSyncMapper;

    @Autowired
    private ProgramClient programClient;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 事务提交后的第一次推送（由 {@code OrderService.updateOrderRelatedData} 的 afterCommit 调用）。
     * <p>
     * 这里仍然只做"尽力而为 + 立即登记失败原因"：调用方已经不在事务里，抛异常没有意义，
     * 失败的行留在 outbox 里由定时任务继续推进。
     *
     * @param payload 事务内从订单行构造的 payload
     */
    public void flushAfterCommit(ProgramSyncPayload payload) {
        if (Objects.isNull(payload) || Objects.isNull(payload.getOrderNumber())) {
            return;
        }
        Long orderNumber = payload.getOrderNumber();
        try {
            // 规则 2：Redis 还没收敛就不许推进 DB
            if (!redisConverged(payload)) {
                log.warn("节目数据同步延后：Redis 尚未收敛，交由重试任务推进 orderNumber : {} programId : {} sellStatus : {}",
                        orderNumber, payload.getProgramId(), payload.getSellStatus());
                retryOrDead(orderNumber, 1, "redis_not_converged");
                record(Metrics.RESULT_NOT_CONVERGED);
                return;
            }
            if (pushProgramData(payload)) {
                markDone(orderNumber);
                record(Metrics.RESULT_SUCCESS);
                return;
            }
            // 规则 3：先复核，再决定是"已生效"还是"真失败"
            if (programApplied(payload)) {
                markDone(orderNumber);
                record(Metrics.RESULT_VERIFIED);
                log.info("节目数据同步复核通过（推送响应丢失，DB 已生效）orderNumber : {}", orderNumber);
                return;
            }
            retryOrDead(orderNumber, 1, "operate_program_data_failed");
            record(Metrics.RESULT_RETRY);
        } catch (Exception e) {
            // 事务已提交，这里绝不能把异常抛回调用方
            log.error("节目数据同步异常，已排入重试 orderNumber : {}", orderNumber, e);
            retryOrDead(orderNumber, 1, e.getMessage());
            record(Metrics.RESULT_ERROR);
        }
    }

    /**
     * 定时任务入口：扫到期的待同步记录并逐条推进。
     *
     * @return 本轮已收敛（含复核通过）的条数
     */
    public int syncDueOrders() {
        List<OrderProgramSyncRecord> recordList =
                orderProgramSyncMapper.selectDue(DateUtils.now(), BATCH_SIZE);
        if (CollectionUtil.isEmpty(recordList)) {
            return 0;
        }
        int settled = 0;
        for (OrderProgramSyncRecord record : recordList) {
            try {
                if (syncOne(record)) {
                    settled++;
                }
            } catch (Exception e) {
                // 单条异常不影响后续；这条会留在 outbox 里等下一轮（next_time 未变，可能仍到期）
                log.error("节目数据同步单条处理异常 orderNumber : {}", record.getOrderNumber(), e);
                record(Metrics.RESULT_ERROR);
            }
        }
        return settled;
    }

    /**
     * 差异扫描：找"已经终态、却从未登记同步"的 V4 订单。
     * <p>
     * 正常链路里不存在这样的行（入队和状态 CAS 在同一个事务里）。真出现了说明有写路径绕过了 outbox，
     * 而这些行没有 payload，无法自动重放——只上报，交人工按订单核对节目服务 DB。
     *
     * @return 本轮发现的可疑订单数
     */
    public int scanTerminalWithoutSync() {
        Date now = DateUtils.now();
        Date from = DateUtils.addMinute(now, -UNSYNCED_SCAN_WINDOW_MINUTES);
        List<Long> orderNumberList = orderProgramSyncMapper.selectTerminalWithoutSync(
                ProgramOrderVersion.V4_VERSION.getValue(), from, now, BATCH_SIZE);
        if (CollectionUtil.isEmpty(orderNumberList)) {
            return 0;
        }
        for (Long orderNumber : orderNumberList) {
            record(Metrics.RESULT_UNSYNCED);
        }
        log.error("发现 {} 笔终态订单未登记节目数据同步（缺 payload 无法自动重放，需人工核对节目服务 DB）orderNumberList : {}",
                orderNumberList.size(), orderNumberList);
        return orderNumberList.size();
    }

    /**
     * 推进一条 outbox 记录。
     *
     * @return true=已收敛（推送成功或复核确认已生效）
     */
    private boolean syncOne(OrderProgramSyncRecord record) {
        Long orderNumber = record.getOrderNumber();
        int attemptedTimes = Objects.isNull(record.getAttempts()) ? 0 : record.getAttempts();
        ProgramSyncPayload payload = parsePayload(record.getPayload());
        if (Objects.isNull(payload)) {
            // payload 损坏：没有任何可重放的内容，直接死信，别让它每轮都空转
            orderProgramSyncMapper.markDead(orderNumber, attemptedTimes, "payload 无法解析");
            record(Metrics.RESULT_DEAD);
            log.error("节目数据同步 payload 无法解析，转人工处理 orderNumber : {} payload : {}",
                    orderNumber, record.getPayload());
            return false;
        }
        // 规则 2：Redis 未收敛 → 拒绝推进 DB
        if (!redisConverged(payload)) {
            retryOrDead(orderNumber, attemptedTimes + 1, "redis_not_converged");
            record(Metrics.RESULT_NOT_CONVERGED);
            return false;
        }
        if (pushProgramData(payload)) {
            markDone(orderNumber);
            record(Metrics.RESULT_SUCCESS);
            return true;
        }
        // 规则 3：复核；节目服务对重复执行是幂等的（V4 只在 LOCK 状态才改），
        // 所以"推成功但响应丢"会在下一次推送里被守卫挡住——先用状态查询把它认出来。
        if (programApplied(payload)) {
            markDone(orderNumber);
            record(Metrics.RESULT_VERIFIED);
            log.info("节目数据同步复核通过（推送响应丢失，DB 已生效）orderNumber : {}", orderNumber);
            return true;
        }
        retryOrDead(orderNumber, attemptedTimes + 1, "operate_program_data_failed");
        record(Metrics.RESULT_RETRY);
        return false;
    }

    /**
     * Redis（权威源）是否已经收敛到目标状态：
     * LOCK 区不再包含这些座位，且目标区（SOLD / NO_SOLD）包含这些座位并且状态正确。
     * <p>
     * 逐座位 GET 而不是 multiGet：{@code multiGetForHash} 会丢掉 null 位（且首个元素为 null 时直接返回空列表），
     * 用它判断"某座位是否还在 LOCK 区"会漏判。
     */
    private boolean redisConverged(ProgramSyncPayload payload) {
        Map<Long, List<Long>> ticketCategorySeatMap = payload.getTicketCategorySeatMap();
        Integer sellStatus = payload.getSellStatus();
        if (Objects.isNull(ticketCategorySeatMap) || ticketCategorySeatMap.isEmpty() || Objects.isNull(sellStatus)) {
            return false;
        }
        boolean sold = Objects.equals(sellStatus, SellStatus.SOLD.getCode());
        try {
            for (Map.Entry<Long, List<Long>> entry : ticketCategorySeatMap.entrySet()) {
                Long ticketCategoryId = entry.getKey();
                List<Long> seatIdList = entry.getValue();
                if (Objects.isNull(ticketCategoryId) || CollectionUtil.isEmpty(seatIdList)) {
                    return false;
                }
                RedisKeyBuild lockKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, payload.getProgramId(), ticketCategoryId);
                RedisKeyBuild targetKey = RedisKeyBuild.createRedisKey(
                        sold ? RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH
                                : RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                        payload.getProgramId(), ticketCategoryId);
                for (Long seatId : seatIdList) {
                    String hashKey = String.valueOf(seatId);
                    SeatVo lockSeatVo = redisCache.getForHash(lockKey, hashKey, SeatVo.class);
                    if (Objects.nonNull(lockSeatVo)) {
                        // Lua 的 HDEL 还没生效：Redis 尚未收敛
                        return false;
                    }
                    SeatVo targetSeatVo = redisCache.getForHash(targetKey, hashKey, SeatVo.class);
                    if (Objects.isNull(targetSeatVo)
                            || !Objects.equals(targetSeatVo.getSellStatus(), sellStatus)) {
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            // Redis 不可用属于"不知道"，按未收敛处理（宁可晚一点推 DB，也不能推早了）
            log.warn("校验 Redis 收敛状态失败，按未收敛处理 orderNumber : {}", payload.getOrderNumber(), e);
            return false;
        }
        return true;
    }

    /**
     * 推送节目数据。
     *
     * @return true=节目服务返回成功；false=业务失败或调用异常（调用方再复核）
     */
    private boolean pushProgramData(ProgramSyncPayload payload) {
        ProgramOperateDataDto programOperateDataDto = buildProgramOperateDataDto(payload);
        try {
            ApiResponse<Boolean> response = programClient.operateProgramData(programOperateDataDto);
            if (Objects.nonNull(response)
                    && Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
                return true;
            }
            log.warn("program服务 operateProgramData 未返回成功 orderNumber : {} response : {}",
                    payload.getOrderNumber(), JSON.toJSONString(response));
            return false;
        } catch (Exception e) {
            // 超时/连接失败：可能已经改成功了，交给 programApplied 复核
            log.warn("program服务 operateProgramData 调用异常 orderNumber : {} dto : {}",
                    payload.getOrderNumber(), JSON.toJSONString(programOperateDataDto), e);
            return false;
        }
    }

    /**
     * 复核：节目服务 DB 里的座位是否已经全部等于目标状态。
     * 用于区分"推送成功但响应丢了"和"真的没生效"。
     */
    private boolean programApplied(ProgramSyncPayload payload) {
        List<Long> seatIdList = payload.getAllSeatIdList();
        Integer sellStatus = payload.getSellStatus();
        if (CollectionUtil.isEmpty(seatIdList) || Objects.isNull(sellStatus)) {
            return false;
        }
        try {
            SeatSellStatusDto seatSellStatusDto = new SeatSellStatusDto();
            seatSellStatusDto.setProgramId(payload.getProgramId());
            seatSellStatusDto.setSeatIdList(seatIdList);
            ApiResponse<List<SeatSellStatusVo>> response = programClient.selectSeatSellStatus(seatSellStatusDto);
            if (Objects.isNull(response) || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
                return false;
            }
            List<SeatSellStatusVo> seatSellStatusVoList = response.getData();
            // 座位必须一个不少地查回来：缺任何一个都说明没收敛
            if (CollectionUtil.isEmpty(seatSellStatusVoList) || seatSellStatusVoList.size() != seatIdList.size()) {
                return false;
            }
            for (SeatSellStatusVo seatSellStatusVo : seatSellStatusVoList) {
                if (!Objects.equals(seatSellStatusVo.getSellStatus(), sellStatus)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("复核节目服务座位状态失败 orderNumber : {}", payload.getOrderNumber(), e);
            return false;
        }
    }

    /**
     * 把 payload 转成节目服务的入参（与支付/取消链路里的 DTO 完全一致）。
     */
    public static ProgramOperateDataDto buildProgramOperateDataDto(ProgramSyncPayload payload) {
        ProgramOperateDataDto programOperateDataDto = new ProgramOperateDataDto();
        programOperateDataDto.setProgramId(payload.getProgramId());
        // 节目服务侧的幂等键用的是创建订单时的 identifierId
        programOperateDataDto.setOrderNumber(payload.getIdentifierId());
        programOperateDataDto.setSeatIdList(payload.getAllSeatIdList());
        programOperateDataDto.setTicketCategoryCountDtoList(payload.getTicketCategoryCountDtoList());
        programOperateDataDto.setSellStatus(payload.getSellStatus());
        programOperateDataDto.setOrderVersion(payload.getOrderVersion());
        return programOperateDataDto;
    }

    private void markDone(Long orderNumber) {
        try {
            orderProgramSyncMapper.markDone(orderNumber);
        } catch (Exception e) {
            // 标记失败会让这条被重复推进：节目服务对重复推送是幂等的，可以接受
            log.error("标记节目数据已同步失败（下轮可能重复推进，节目服务侧幂等）orderNumber : {}", orderNumber, e);
        }
    }

    /**
     * 排下一轮重试；已到上限则转死信。
     *
     * @param attemptedTimes 累计已尝试次数（含本次）
     */
    private void retryOrDead(Long orderNumber, int attemptedTimes, String reason) {
        String error = truncate(reason);
        if (attemptedTimes >= MAX_ATTEMPTS) {
            try {
                orderProgramSyncMapper.markDead(orderNumber, attemptedTimes, error);
            } catch (Exception e) {
                log.error("标记节目数据同步死信失败 orderNumber : {}", orderNumber, e);
            }
            record(Metrics.RESULT_DEAD);
            log.error("节目数据同步达到最大重试次数，转人工处理 orderNumber : {} attempts : {} reason : {}",
                    orderNumber, attemptedTimes, error);
            return;
        }
        Date nextTime = DateUtils.addSecond(DateUtils.now(), (int) backoffSeconds(attemptedTimes));
        try {
            orderProgramSyncMapper.markRetry(orderNumber, attemptedTimes, nextTime, error);
        } catch (Exception e) {
            log.error("登记节目数据同步重试失败 orderNumber : {}", orderNumber, e);
        }
        log.warn("节目数据同步未完成，已排下一轮 orderNumber : {} attempts : {} nextTime : {} reason : {}",
                orderNumber, attemptedTimes, DateUtils.formatDateTime(nextTime), error);
    }

    /**
     * 指数退避：30s * 2^(n-1)，封顶 10 分钟。
     */
    private long backoffSeconds(int attemptedTimes) {
        int exponent = Math.max(attemptedTimes - 1, 0);
        // 防止移位溢出：超过 5 次后一律取封顶值
        long backoff = exponent >= 5 ? MAX_BACKOFF_SECONDS
                : Math.min(BASE_BACKOFF_SECONDS << exponent, MAX_BACKOFF_SECONDS);
        return Math.max(backoff, BASE_BACKOFF_SECONDS);
    }

    private ProgramSyncPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(payloadJson, ProgramSyncPayload.class);
        } catch (Exception e) {
            log.warn("解析节目数据同步 payload 失败", e);
            return null;
        }
    }

    private String truncate(String error) {
        if (Objects.isNull(error)) {
            return null;
        }
        return error.length() <= ERROR_MAX_LENGTH ? error : error.substring(0, ERROR_MAX_LENGTH);
    }

    private void record(String result) {
        BusinessMetrics.increment(meterRegistry, Metrics.ORDER_PROGRAM_SYNC_TOTAL, Metrics.RESULT, result);
    }
}
