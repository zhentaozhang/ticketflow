package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.domain.PendingOrder;
import com.ticketflow.domain.ProgramRecord;
import com.ticketflow.domain.ReconciliationTaskData;
import com.ticketflow.domain.SeatRecord;
import com.ticketflow.domain.TicketCategoryRecord;
import com.ticketflow.dto.TicketCategoryListDto;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.HandleStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.enums.RecordType;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.handler.ProgramRecordHandler;
import com.ticketflow.service.handler.SeatHandler;
import com.ticketflow.service.handler.TicketRemainNumberHandler;
import com.ticketflow.vo.TicketCategoryDetailVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.GLIDE_LINE;

/**
 * 订单对账/恢复任务服务——处理订单与库存的最终一致性。
 *
 * 核心场景：
 *   1. 延迟队列到期 → 检查订单支付状态，未支付则取消并恢复库存
 *   2. 对账任务 → 对比订单记录与票档/座位实际状态
 *   3. 购票人记录回调 → 处理余票扣减与数据同步
 *
 * 涉及 Redis 库存校验、座位锁定状态恢复、票档余量回滚等一致性操作
 */
@Slf4j
@Service
public class OrderTaskService {
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    
    @Autowired
    private ProgramClient programClient;
    
    @Autowired
    private ProgramRecordHandler programRecordHandler;
    
    @Autowired
    private SeatHandler seatHandler;
    
    @Autowired
    private TicketRemainNumberHandler ticketRemainNumberHandler;
    
    @Autowired
    private OrderProgramMapper orderProgramMapper;

    @Autowired
    private OrderService orderService;

    /**
     * PENDING 裁决窗口：请求侧降级为已受理后，等待消费侧建单的最长时间。
     * 超过该窗口仍未建单，视为发送未落地，回滚 Redis 座位。
     */
    private static final long PENDING_WINDOW_MS = 60_000L;

    // ==================== 对账任务入口 ====================
    
    /**
     * 执行对账任务（以数据库为准）
     * 
     * 核心逻辑：
     * 1. 查询Redis中的节目记录（hash类型，key: ticketflow-d_mai_program_record_{programId}）
     * 2. 查询数据库中未对账的购票人订单记录
     * 3. 对比找出数据库有但Redis没有的座位记录
     * 4. 将缺失的记录补偿到Redis
     * 5. 更新数据库对账状态、转移Redis记录到PROGRAM_RECORD_FINISH
     * 
     * 说明：
     * - 只处理“数据库有、Redis没有”的情况
     * - “Redis有、数据库没有”的情况由DISCARD_ORDER机制处理（消费延迟/创建失败）
     * 
     * @param programId 节目id
     * @return 对账任务结果，包含已补偿到Redis的记录；无待对账记录时返回null
     */
    public ReconciliationTaskData reconciliationTask(Long programId) {
        // 步骤1: 查询Redis中的节目记录
        // key格式: recordType_identifierId_userId
        // value格式: ProgramRecord的JSON字符串
        Map<String, String> redisRecordMap = redisCache.getAllMapForHash(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId), String.class);
        
        // 步骤2: 查询数据库未处理的订单
        List<OrderProgram> orderPrograms = orderProgramMapper.selectList(
                Wrappers.lambdaQuery(OrderProgram.class)
                        .eq(OrderProgram::getHandleStatus, HandleStatus.NO_HANDLE.getCode())
                        .eq(OrderProgram::getProgramId, programId));
        if (CollectionUtil.isEmpty(orderPrograms)) {
            // 没有待对账的订单
            return null;
        }
        
        // 步骤3: 查询数据库并对比，构建需要补偿的记录
        // 返回格式: key=identifierId_userId, value=ProgramRecord列表(按reduce/changeStatus/increase排序)
        Map<String, List<ProgramRecord>> needToRedisRecordMap = findNeedCompensationRecords(orderPrograms, redisRecordMap);
        
        // 步骤4: 执行补偿（如果有需要补偿的记录）、更新数据库状态、转移Redis记录
        Map<String, ProgramRecord> addedRecords = compensateAndFinalize(programId, needToRedisRecordMap, redisRecordMap);
        
        // 步骤5: 构建返回结果
        ReconciliationTaskData result = new ReconciliationTaskData();
        result.setProgramId(programId);
        result.setAddRedisRecordData(addedRecords);
        return result;
    }

    /**
     * DISCARD_ORDER 补偿：回滚因建单失败被丢弃订单在 Redis 中已扣减的座位与余票。
     * <p>
     * 覆盖场景：consumer 建单失败（Feign 扣减失败 / DB 建单异常）时只写 DISCARD_ORDER 留痕，
     * Redis 侧（producer Lua 已扣）无回滚。该方法按节目读取丢弃记录逐条回滚。
     * <p>
     * 幂等：rollbackProgramSeatByDiscard 内置双重安全约束
     * （订单已存在跳过、座位不在锁定集合跳过），重复执行无害；
     * 回滚成功（含终态跳过）后移除当条记录（LREM），
     * 失败保留由下轮对账重试兜底。
     *
     * @param programId 节目 id
     */
    public void discardOrderCompensation(Long programId) {
        RedisKeyBuild discardOrderKey = RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, programId);
        Long length = redisCache.lenForList(discardOrderKey);
        if (length == null || length <= 0) {
            return;
        }
        List<DiscardOrder> discardOrderList = redisCache.rangeForList(discardOrderKey, 0, length - 1, DiscardOrder.class);
        if (CollectionUtil.isEmpty(discardOrderList)) {
            return;
        }
        for (DiscardOrder discardOrder : discardOrderList) {
            if (Objects.isNull(discardOrder) || Objects.isNull(discardOrder.getOrderCreateMq())) {
                continue;
            }
            try {
                orderService.rollbackProgramSeatByDiscard(discardOrder.getOrderCreateMq());
                // 回滚成功（含订单已存在/座位已释放等终态跳过）后移除记录，避免 DISCARD_ORDER 无限增长
                redisCache.removeForList(discardOrderKey, discardOrder, 1);
            } catch (Exception e) {
                log.error("DISCARD_ORDER 补偿回滚失败 节目id : {} 订单号 : {}",
                        programId, discardOrder.getOrderCreateMq().getOrderNumber(), e);
            }
        }
    }
    
    /**
     * PENDING 补偿：请求侧 Kafka 发送确认超时降级为"已受理"的订单，裁决 Redis 扣减是否落地。
     * <p>
     * 覆盖场景：producer Lua 已扣座位/余票，但发送确认超时（消息可能已发送、也可能未发送），
     * 消费侧建单状态未知。该方法按节目读取 PENDING 记录逐条裁决：
     * <ul>
     *   <li>仅处理写入超 60s 的记录（给消费侧留出建单窗口）；</li>
     *   <li>订单已建（订单行存在或 ORDER_MQ 标记存在）→ 直接移除记录；</li>
     *   <li>订单未建 → rollbackProgramSeatByDiscard 回滚 Redis 座位后移除记录。</li>
     * </ul>
     * 幂等：rollbackProgramSeatByDiscard 内置双重安全约束（订单已存在跳过、座位不在锁定集合跳过），
     * 同时容忍"late-failureCallback 已提前回滚"的状态；回滚失败保留记录由下轮对账重试兜底。
     *
     * @param programId 节目 id
     */
    public void pendingOrderCompensation(Long programId) {
        RedisKeyBuild pendingOrderKey = RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_CREATE_PENDING, programId);
        Long length = redisCache.lenForList(pendingOrderKey);
        if (length == null || length <= 0) {
            return;
        }
        List<PendingOrder> pendingOrderList = redisCache.rangeForList(pendingOrderKey, 0, length - 1, PendingOrder.class);
        if (CollectionUtil.isEmpty(pendingOrderList)) {
            return;
        }
        for (PendingOrder pendingOrder : pendingOrderList) {
            if (Objects.isNull(pendingOrder) || Objects.isNull(pendingOrder.getOrderCreateMq())) {
                continue;
            }
            OrderCreateMq orderCreateMq = pendingOrder.getOrderCreateMq();
            long age = System.currentTimeMillis() - orderCreateMq.getCreateOrderTime().getTime();
            if (age < PENDING_WINDOW_MS) {
                // 未超窗：等待消费侧建单，下轮再裁决
                continue;
            }
            // 已建单判定：订单行存在 或 ORDER_MQ 标记存在
            Long orderCount = orderService.count(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderCreateMq.getOrderNumber()));
            boolean orderCreated = (orderCount != null && orderCount > 0)
                    || Objects.nonNull(redisCache.get(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.ORDER_MQ, orderCreateMq.getOrderNumber()), String.class));
            if (!orderCreated) {
                try {
                    orderService.rollbackProgramSeatByDiscard(orderCreateMq);
                } catch (Exception e) {
                    // 回滚失败保留记录，由下轮对账重试兜底
                    log.error("PENDING 补偿回滚失败 节目id : {} 订单号 : {}",
                            programId, orderCreateMq.getOrderNumber(), e);
                    continue;
                }
            }
            // 裁决完成（建单成功 / 回滚成功含终态跳过）后移除记录，避免 PENDING 无限增长
            redisCache.removeForList(pendingOrderKey, pendingOrder, 1);
        }
    }

    // ==================== 查找需要补偿的记录 ====================
    
    /**
     * 查找需要向Redis补偿的记录（以数据库为准）
     * 
     * 执行流程：
     * 1. 查询OrderTicketUserRecord表，获取未对账的购票人订单记录
     * 2. 按 identifierId_userId 分组
     * 3. 对比Redis，找出数据库有但Redis没有的记录
     * 
     * @param orderPrograms 未处理的订单列表
     * @param redisRecordMap Redis中的节目记录
     * @return key: identifierId_userId, value: ProgramRecord列表(按reduce/changeStatus/increase排序)
     */
    private Map<String, List<ProgramRecord>> findNeedCompensationRecords(List<OrderProgram> orderPrograms, Map<String, String> redisRecordMap) {
        // 1. 批量查询未对账的购票人订单记录
        List<Long> orderNumbers = orderPrograms.stream().map(OrderProgram::getOrderNumber).collect(Collectors.toList());
        List<OrderTicketUserRecord> dbRecords = orderTicketUserRecordMapper.selectList(
                Wrappers.lambdaQuery(OrderTicketUserRecord.class)
                        .in(OrderTicketUserRecord::getOrderNumber, orderNumbers)
                        .eq(OrderTicketUserRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        if (CollectionUtil.isEmpty(dbRecords)) {
            return Collections.emptyMap();
        }
        
        // 2. 按 identifierId_userId 分组（同一用户同一订单的记录分为一组）
        Map<String, List<OrderTicketUserRecord>> dbRecordsByUser = dbRecords.stream()
                .collect(Collectors.groupingBy(r -> r.getIdentifierId() + GLIDE_LINE + r.getUserId()));
        
        // 3. 对比并构建补偿记录
        Map<String, List<ProgramRecord>> result = new HashMap<>(64);
        for (Map.Entry<String, List<OrderTicketUserRecord>> entry : dbRecordsByUser.entrySet()) {
            List<ProgramRecord> programRecords = buildProgramRecordsForUser(entry.getValue(), redisRecordMap);
            if (CollectionUtil.isNotEmpty(programRecords)) {
                result.put(entry.getKey(), programRecords);
            }
        }
        return result;
    }
    
    /**
     * 执行补偿并完成对账（更新数据库状态 + 转移Redis记录）
     * 
     * 核心逻辑：
     * - 如果有需要补偿的记录，先执行补偿逻辑（逆向还原余票、清除缓存）
     * - 无论是否需要补偿，都要更新数据库对账状态和转移Redis记录
     * 
     * @param programId 节目id
     * @param needToRedisRecordMap 需要补偿到Redis的记录（可能为空）
     * @param redisRecordMap Redis中的节目记录
     * @return 已补偿的记录（如果无需补偿则为空Map）
     */
    private Map<String, ProgramRecord> compensateAndFinalize(Long programId, 
                                                              Map<String, List<ProgramRecord>> needToRedisRecordMap,
                                                              Map<String, String> redisRecordMap) {
        Map<String, ProgramRecord> completeRedisCordMap = new HashMap<>(64);
        
        // 1. 如果有需要补偿的记录，执行补偿逻辑
        if (CollectionUtil.isNotEmpty(needToRedisRecordMap)) {
            // 1.1 收集所有涉及的票档ID
            Set<Long> ticketCategoryIdSet = getTicketCategoryIdSet(needToRedisRecordMap);
            
            // 1.2 调用节目服务获取票档的当前余票数量
            TicketCategoryListDto ticketCategoryListDto = new TicketCategoryListDto();
            ticketCategoryListDto.setProgramId(programId);
            ticketCategoryListDto.setTicketCategoryIdList(ticketCategoryIdSet);
            ApiResponse<List<TicketCategoryDetailVo>> programApiResponse = programClient.selectList(ticketCategoryListDto);
            if (!Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                throw new TicketFlowFrameException(programApiResponse);
            }
            List<TicketCategoryDetailVo> ticketCategoryDetailVoList = programApiResponse.getData();
            if (CollectionUtil.isEmpty(ticketCategoryDetailVoList)) {
                throw new TicketFlowFrameException(BaseCode.RPC_RESULT_DATA_EMPTY);
            }
            // 校验节目服务返回的票档必须覆盖所有补偿涉及的票档，缺失说明数据不一致，宁可失败报警也不写入错误补偿数据
            Set<Long> returnedCategoryIds = ticketCategoryDetailVoList.stream()
                    .map(TicketCategoryDetailVo::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!returnedCategoryIds.containsAll(ticketCategoryIdSet)) {
                throw new TicketFlowFrameException(BaseCode.RPC_RESULT_DATA_EMPTY);
            }
            
            // 1.3 构建票档余票数量Map
            Map<Long, Long> ticketCategoryRemainNumberMap = ticketCategoryDetailVoList.stream()
                    .collect(Collectors.toMap(TicketCategoryDetailVo::getId, TicketCategoryDetailVo::getRemainNumber, 
                            (v1, v2) -> v2));
            
            // 1.4 逆向还原每笔订单的余票变化
            for (Map.Entry<String, List<ProgramRecord>> programRecordEntry : needToRedisRecordMap.entrySet()) {
                restoreSingleOrder(programRecordEntry.getValue(), ticketCategoryRemainNumberMap);
            }
            
            // 1.5 构建补偿记录Map
            for (Map.Entry<String, List<ProgramRecord>> redisRecordEntry : needToRedisRecordMap.entrySet()) {
                String key = redisRecordEntry.getKey();
                for (ProgramRecord programRecord : redisRecordEntry.getValue()) {
                    completeRedisCordMap.put(programRecord.getRecordType() + GLIDE_LINE + key, programRecord);
                }
            }
            
            // 1.6 删除Redis中相关票档的座位和余票缓存
            for (Long ticketCategoryId : ticketCategoryIdSet) {
                seatHandler.delRedisSeatData(programId, ticketCategoryId);
                ticketRemainNumberHandler.delRedisSeatData(programId, ticketCategoryId);
            }
        }
        
        // 2. 更新数据库状态 + 转移Redis记录（无论是否需要补偿都要执行）
        // programRecordHandler.add 会执行：
        //   - 更新Order/OrderTicketUser/OrderProgram/OrderTicketUserRecord的对账状态为RECONCILIATION_SUCCESS
        //   - 从PROGRAM_RECORD中删除旧记录
        //   - 将所有记录添加到PROGRAM_RECORD_FINISH
        programRecordHandler.add(0, programId, completeRedisCordMap, redisRecordMap);
        
        return completeRedisCordMap;
    }
    
    /**
     * 为单个用户构建ProgramRecord列表
     * 
     * 执行流程：
     * 1. 按记录类型(reduce/changeStatus/increase)分组
     * 2. 对每种类型，找出数据库有但Redis没有的座位
     * 3. 构建ProgramRecord
     * 4. 按 reduce(-1) -> changeStatus(0) -> increase(1) 排序
     * 
     * @param userRecords 该用户的所有购票人订单记录
     * @param redisRecordMap Redis中的节目记录
     * @return ProgramRecord列表，按记录类型排序
     */
    private List<ProgramRecord> buildProgramRecordsForUser(List<OrderTicketUserRecord> userRecords, Map<String, String> redisRecordMap) {
        // 1. 按记录类型分组（reduce/changeStatus/increase）
        Map<String, List<OrderTicketUserRecord>> recordsByType = userRecords.stream()
                .collect(Collectors.groupingBy(OrderTicketUserRecord::getRecordTypeValue));
        
        // 2. 遍历每种记录类型，构建ProgramRecord
        List<ProgramRecord> result = new ArrayList<>();
        for (Map.Entry<String, List<OrderTicketUserRecord>> entry : recordsByType.entrySet()) {
            String recordType = entry.getKey();
            List<OrderTicketUserRecord> typeRecords = entry.getValue();
            
            // 2.1 找出数据库有但Redis没有的座位
            List<OrderTicketUserRecord> needCompensate = findMissingInRedis(typeRecords, redisRecordMap, recordType);
            if (CollectionUtil.isEmpty(needCompensate)) {
                continue;  // 该类型不需要补偿
            }
            
            // 2.2 构建ProgramRecord
            result.add(buildProgramRecord(needCompensate, recordType));
        }
        
        // 3. 按记录类型排序：reduce(-1) -> changeStatus(0) -> increase(1)
        result.sort(Comparator.comparingInt(pr -> RecordType.getCodeByValue(pr.getRecordType())));
        return result;
    }
    
    /**
     * 找出数据库有但Redis没有的座位记录
     * 
     * 执行流程：
     * 1. 构建Redis的key: recordType_identifierId_userId
     * 2. 从Redis记录中提取座位ID集合
     * 3. 过滤出数据库中存在但Redis中不存在的座位
     * 
     * @param dbRecords 数据库中的记录
     * @param redisRecordMap Redis中的节目记录
     * @param recordType 记录类型(reduce/changeStatus/increase)
     * @return 需要补偿的记录列表
     */
    private List<OrderTicketUserRecord> findMissingInRedis(
            List<OrderTicketUserRecord> dbRecords, Map<String, String> redisRecordMap, String recordType) {
        if (CollectionUtil.isEmpty(dbRecords)) {
            return Collections.emptyList();
        }
        
        // 1. 构建Redis的key: recordType_identifierId_userId
        OrderTicketUserRecord first = dbRecords.get(0);
        String redisKey = recordType + GLIDE_LINE + first.getIdentifierId() + GLIDE_LINE + first.getUserId();
        
        // 2. 从Redis记录中提取座位ID集合
        Set<Long> redisSeatIds = extractRedisSeatIds(redisRecordMap, redisKey);
        
        // 3. 返回数据库有但Redis没有的座位
        return dbRecords.stream()
                .filter(r -> !redisSeatIds.contains(r.getSeatId()))
                .collect(Collectors.toList());
    }
    
    /**
     * 从Redis记录中提取座位ID集合
     * 
     * Redis记录结构：
     * ProgramRecord
     *   └── ticketCategoryRecordList (List<TicketCategoryRecord>)
     *         └── seatRecordList (List<SeatRecord>)
     *               └── seatId
     * 
     * @param redisRecordMap Redis中的节目记录
     * @param key Redis的field key
     * @return 座位ID集合
     */
    private Set<Long> extractRedisSeatIds(Map<String, String> redisRecordMap, String key) {
        // 1. 检查Redis记录是否存在
        if (redisRecordMap == null || redisRecordMap.get(key) == null) {
            return Collections.emptySet();
        }
        
        // 2. 解析JSON为ProgramRecord
        ProgramRecord record = JSON.parseObject(redisRecordMap.get(key), ProgramRecord.class);
        if (record == null || CollectionUtil.isEmpty(record.getTicketCategoryRecordList())) {
            return Collections.emptySet();
        }
        
        // 3. 提取所有座位ID
        return record.getTicketCategoryRecordList().stream()
                .filter(tcr -> CollectionUtil.isNotEmpty(tcr.getSeatRecordList()))
                .flatMap(tcr -> tcr.getSeatRecordList().stream())
                .map(SeatRecord::getSeatId)
                .collect(Collectors.toSet());
    }
    
    /**
     * 构建ProgramRecord
     * 
     * 执行流程：
     * 1. 将数据库记录转换为SeatRecord，并设置座位状态
     * 2. 按票档ID分组
     * 3. 构建ProgramRecord
     * 
     * @param records 需要补偿的数据库记录
     * @param recordType 记录类型(reduce/changeStatus/increase)
     * @return ProgramRecord
     */
    private ProgramRecord buildProgramRecord(List<OrderTicketUserRecord> records, String recordType) {
        Integer recordTypeCode = RecordType.getCodeByValue(recordType);
        
        // 1. 转换为SeatRecord并设置状态，然后按票档ID分组
        List<TicketCategoryRecord> ticketCategoryRecords = records.stream()
                .map(r -> {
                    SeatRecord seat = new SeatRecord();
                    seat.setSeatId(r.getSeatId());
                    seat.setTicketCategoryId(r.getTicketCategoryId());
                    seat.setTicketUserId(r.getTicketUserId());
                    // 根据记录类型设置座位的前后状态
                    setSeatStatusByRecordType(seat, recordTypeCode);
                    return seat;
                })
                .collect(Collectors.groupingBy(SeatRecord::getTicketCategoryId))  // 按票档分组
                .entrySet().stream()
                .map(e -> {
                    TicketCategoryRecord tcr = new TicketCategoryRecord();
                    tcr.setTicketCategoryId(e.getKey());
                    tcr.setSeatRecordList(e.getValue());
                    return tcr;
                })
                .collect(Collectors.toList());
        
        // 2. 构建ProgramRecord
        ProgramRecord programRecord = new ProgramRecord();
        programRecord.setRecordType(recordType);
        programRecord.setTimestamp(System.currentTimeMillis());
        programRecord.setTicketCategoryRecordList(ticketCategoryRecords);
        return programRecord;
    }
    
    // ==================== 座位状态设置 ====================
    
    /**
     * 根据记录类型设置座位的前后状态
     * 
     * 状态变化规则：
     * - reduce(扣减余票)：未售(NO_SOLD) -> 锁定(LOCK)
     * - changeStatus(支付成功)：锁定(LOCK) -> 已售(SOLD)
     * - increase(取消订单)：锁定(LOCK) -> 未售(NO_SOLD)
     * 
     * @param seatRecord 座位记录
     * @param recordTypeCode 记录类型编码（-1=reduce, 0=changeStatus, 1=increase）
     */
    private void setSeatStatusByRecordType(SeatRecord seatRecord, Integer recordTypeCode) {
        if (Objects.equals(recordTypeCode, RecordType.REDUCE.getCode())) {
            // 扣减余票：未售 -> 锁定
            seatRecord.setBeforeStatus(SellStatus.NO_SOLD.getCode());
            seatRecord.setAfterStatus(SellStatus.LOCK.getCode());
        } else if (Objects.equals(recordTypeCode, RecordType.CHANGE_STATUS.getCode())) {
            // 改变状态（支付成功）：锁定 -> 已售
            seatRecord.setBeforeStatus(SellStatus.LOCK.getCode());
            seatRecord.setAfterStatus(SellStatus.SOLD.getCode());
        } else if (Objects.equals(recordTypeCode, RecordType.INCREASE.getCode())) {
            // 增加余票（取消订单）：锁定 -> 未售
            seatRecord.setBeforeStatus(SellStatus.LOCK.getCode());
            seatRecord.setAfterStatus(SellStatus.NO_SOLD.getCode());
        }
    }
    
    // ==================== 执行补偿 ====================
    
    /**
     * 逆向还原单笔订单的所有ProgramRecord
     * 
     * 背景说明：
     * - 补偿时需要填充每个票档的beforeAmount/afterAmount/changeAmount
     * - 但我们只知道当前的余票数量，需要逆向还原出每一步操作的前后余票
     * 
     * 执行流程：
     * 1. 将记录列表倒序（increase->changeStatus->reduce），从最新的记录开始还原
     * 2. 对每条记录做逆向计算：
     *    - reduce(扣减): 逆向要加回，beforeAmount = current + changeAmt
     *    - increase(恢复): 逆向要再扣减，beforeAmount = current - changeAmt
     *    - changeStatus: 不改票数
     * 3. 还原完毕后再反转回来
     * 
     * @param programRecords 按时间正序排列的记录列表（reduce->changeStatus->increase）
     * @param ticketCategoryRemainNumberMap key:票档id value:当前余票数量（会被本方法修改）
     */
    public void restoreSingleOrder(List<ProgramRecord> programRecords, Map<Long, Long> ticketCategoryRemainNumberMap) {
        // 步骤1: 倒序，从最新的记录开始还原（increase->changeStatus->reduce）
        Collections.reverse(programRecords);
        
        // 步骤2: 逐条记录做逆向计算
        for (ProgramRecord programRecord : programRecords) {
            String recordType = programRecord.getRecordType();
            
            // 2.1 统计本条记录中每个票档的变化数量
            Map<Long, Long> changeAmtMap = programRecord.getTicketCategoryRecordList().stream()
                    .collect(Collectors.groupingBy(
                            TicketCategoryRecord::getTicketCategoryId, 
                            Collectors.summingLong(tcr -> tcr.getSeatRecordList().size())));
            
            // 2.2 对每个票档做逆向计算
            for (Map.Entry<Long, Long> entry : changeAmtMap.entrySet()) {
                Long categoryId = entry.getKey();
                long changeAmt = entry.getValue();
                long current = ticketCategoryRemainNumberMap.getOrDefault(categoryId, 0L);
                
                long beforeAmount;
                long afterAmount;
                
                if (Objects.equals(recordType, RecordType.REDUCE.getValue())) {
                    // 原操作扣减 -> 逆向要加回
                    afterAmount = current;
                    beforeAmount = current + changeAmt;
                    ticketCategoryRemainNumberMap.put(categoryId, beforeAmount);
                } else if (Objects.equals(recordType, RecordType.INCREASE.getValue())) {
                    // 原操作恢复 -> 逆向要再扣减
                    afterAmount = current;
                    beforeAmount = current - changeAmt;
                    ticketCategoryRemainNumberMap.put(categoryId, beforeAmount);
                } else {
                    // 状态变更，不改票数
                    beforeAmount = current;
                    afterAmount = current;
                }
                
                // 2.3 回填刨所有对应TicketCategoryRecord的字段
                for (TicketCategoryRecord tcr : programRecord.getTicketCategoryRecordList()) {
                    if (tcr.getTicketCategoryId().equals(categoryId)) {
                        tcr.setBeforeAmount(beforeAmount);
                        tcr.setAfterAmount(afterAmount);
                        tcr.setChangeAmount(Objects.equals(recordType, RecordType.CHANGE_STATUS.getValue()) ? 0L : changeAmt);
                    }
                }
            }
        }
        
        // 步骤3: 还原完毕后反转回来（reduce->changeStatus->increase）
        Collections.reverse(programRecords);
    }
    
    /**
     * 获取所有涉及的票档ID集合
     */
    private Set<Long> getTicketCategoryIdSet(Map<String, List<ProgramRecord>> needToRedisRecordMap) {
        return needToRedisRecordMap.values().stream()
                .flatMap(List::stream)
                .flatMap(pr -> pr.getTicketCategoryRecordList().stream())
                .map(TicketCategoryRecord::getTicketCategoryId)
                .collect(Collectors.toSet());
    }
}
