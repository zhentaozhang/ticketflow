package com.ticketflow.service.handler;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.ProgramRecord;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.HandleStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.SplitUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.ticketflow.constant.Constant.GLIDE_LINE;

/**
 * Redis-DB 对账流水处理器。
 * 定期将 Redis 中的操作记录写回 MySQL 并清除残留锁数据，
 * 确保极端故障下座位数据最终一致。
 *
 * 核心补偿逻辑：
 *   addCompensateRecord()  → 扫描未完成记录 → 补齐完整记录到 Redis
 *   modifyLockOccupySeat()  → 清理过期 lock 占位
 *   handOperateData()       → 将 Redis 中完整的操作记录落库
 */
@Slf4j
@Component
public class ProgramRecordHandler {

    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;
    
    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    
    @Autowired
    private OrderProgramMapper orderProgramMapper;
    
    /**
     * 向redis中添加补偿的记录，从未完成记录中转移到完整的记录。
     * 重试机制：最大 5 次，失败时 sleep(1s) 后递归重试。
     * 注意：@Transactional 只管 DB，Redis 操作在外部，失败会进 catch 重试。
     * */
    @Transactional(rollbackFor = Exception.class)
    public void add(int retryCount,Long programId,
                    Map<String, ProgramRecord> completeRedisCordMap,
                    Map<String, String> totalProgramRecordMap){
        int maxRetryCount = 5;
        if (retryCount > maxRetryCount) {
            log.error("添加记录流水失败超过最大重试次数,retryCount:{} programId:{}, completeRedisCordMap:{}, " +
                    "totalProgramRecordMap:{}", retryCount,programId, completeRedisCordMap, totalProgramRecordMap);
            throw new TicketFlowFrameException(BaseCode.MAX_RETRY_COUNT);
        }
        try {
            Set<String> keyList = new HashSet<>();
            //把数据库中的订单、购票人订单、购票人订单记录都修改成对账完成状态
            addKeyList(keyList,completeRedisCordMap);
            addKeyList(keyList,totalProgramRecordMap);
            for (final String key : keyList) {
                String[] split = SplitUtil.toSplit(key);
                Long identifierId = Long.valueOf(split[0]);
                Long userId = Long.valueOf(split[1]);
                int result = updateDbOrderTicketUserRecordStatus(programId, identifierId, userId,
                        ReconciliationStatus.RECONCILIATION_SUCCESS);
                log.info("修改数据库记录流水成功, programId:{}, identifierId:{}, userId:{}, result:{}", 
                        programId, identifierId, userId, result);
            }
            if (CollectionUtil.isNotEmpty(totalProgramRecordMap)) {
                //从旧地记录中删除
                redisCache.delForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId),
                        totalProgramRecordMap.keySet());
            }
            if (CollectionUtil.isNotEmpty(totalProgramRecordMap)) {
                //目前所有的记录添加到完成的记录中 key：记录类型_记录标识_用户id value：记录标识
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId), 
                        totalProgramRecordMap);
            }
            if (CollectionUtil.isNotEmpty(completeRedisCordMap)) {
                //将新补充的记录添加到redis对比完成的记录中
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId), 
                        completeRedisCordMap);
                log.info("添加记录流水成功, programId:{}, completeRedisCordMap:{}, totalProgramRecordMap:{}", 
                        programId, completeRedisCordMap, totalProgramRecordMap);
            }
        }catch (Exception e) {
            log.warn("添加记录流水失败进行重试, programId:{}, completeRedisCordMap:{}, totalProgramRecordMap:{}", 
                    programId, completeRedisCordMap, totalProgramRecordMap, e);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                log.error("Thread sleep interrupted", ex);
            }
            retryCount++;
            add(retryCount, programId, completeRedisCordMap, totalProgramRecordMap);
        }
    }
    
    public void addKeyList(Set<String> keyList,Map<String,?> map){
        if (CollectionUtil.isEmpty(map)) {
            return;
        }
        for (final Entry<String, ?> entry : map.entrySet()) {
            String[] split = SplitUtil.toSplit(entry.getKey());
            keyList.add(split[1] + GLIDE_LINE + split[2]);
        }
    }
    
    @Transactional(rollbackFor = Exception.class)
    public int updateDbOrderTicketUserRecordStatus(Long programId, Long identifierId, Long userId, ReconciliationStatus reconciliationStatus) {
        List<Order> orderList = orderMapper.selectList(Wrappers.lambdaQuery(Order.class).eq(Order::getProgramId, programId).eq(Order::getIdentifierId, identifierId).eq(Order::getUserId, userId).eq(Order::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        if (CollectionUtil.isEmpty(orderList)) {
            return 0;
        }
        Order updateOrder = new Order();
        updateOrder.setReconciliationStatus(reconciliationStatus.getCode());
        //将订单的对账状态更新为已对账
        orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getProgramId, programId)
                .eq(Order::getIdentifierId, identifierId)
                .eq(Order::getUserId, userId)
                .eq(Order::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        Long orderNumber = orderList.get(0).getOrderNumber();
        //将购票人订单的对账状态更新为已对账
        OrderTicketUser updateOrderTicketUser = new OrderTicketUser();
        updateOrderTicketUser.setReconciliationStatus(reconciliationStatus.getCode());
        orderTicketUserMapper.update(updateOrderTicketUser,Wrappers.lambdaUpdate(OrderTicketUser.class)
                .eq(OrderTicketUser::getOrderNumber, orderNumber)
                .eq(OrderTicketUser::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        //将订单节目的对账状态更新为已对账
        OrderProgram updateOrderProgram = new OrderProgram();
        updateOrderProgram.setHandleStatus(HandleStatus.YES_HANDLE.getCode());
        orderProgramMapper.update(updateOrderProgram,Wrappers.lambdaUpdate(OrderProgram.class)
                .eq(OrderProgram::getOrderNumber, orderNumber)
                .eq(OrderProgram::getHandleStatus, HandleStatus.NO_HANDLE.getCode())
                .eq(OrderProgram::getProgramId, programId));
        //将购票人订单记录的对账状态更新为已对账
        OrderTicketUserRecord updateOrderTicketUserRecord = new OrderTicketUserRecord();
        updateOrderTicketUserRecord.setReconciliationStatus(reconciliationStatus.getCode());
        return orderTicketUserRecordMapper.update(updateOrderTicketUserRecord,Wrappers.lambdaUpdate(OrderTicketUserRecord.class)
                .eq(OrderTicketUserRecord::getOrderNumber, orderNumber)
                .eq(OrderTicketUserRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
    }
}
