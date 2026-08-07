package com.ticketflow.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.TicketCategoryAddDto;
import com.ticketflow.dto.TicketCategoryDto;
import com.ticketflow.dto.TicketCategoryListDto;
import com.ticketflow.entity.Program;
import com.ticketflow.entity.TicketCategory;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ProgramMapper;
import com.ticketflow.mapper.TicketCategoryMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.cache.local.LocalCacheTicketCategory;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.vo.TicketCategoryDetailVo;
import com.ticketflow.vo.TicketCategoryVo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketflow.core.DistributedLockConstants.GET_REMAIN_NUMBER_LOCK;
import static com.ticketflow.core.DistributedLockConstants.GET_TICKET_CATEGORY_LOCK;
import static com.ticketflow.core.DistributedLockConstants.REMAIN_NUMBER_LOCK;
import static com.ticketflow.core.DistributedLockConstants.TICKET_CATEGORY_LOCK;

/**
 * 票档服务核心逻辑。
 *   缓存查询：Caffeine（按 programId）+ Redis（最新余票数）
 *   余票更新：下单时 HINCRBY 扣减，取消/支付后 Lua 脚本原子恢复
 *   管理端：票档新增（缓存双删）、批量禁用
 */
@Slf4j
@Service
public class TicketCategoryService extends ServiceImpl<TicketCategoryMapper, TicketCategory> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private LocalCacheTicketCategory localCacheTicketCategory;
    
    @Autowired
    private ProgramMapper programMapper;
    
    /**
     * 新增票档。
     * 前置校验节目存在性，写入 DB 后返回票档 ID。
     *
     * @param ticketCategoryAddDto 票档新增参数
     * @return 票档 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long add(TicketCategoryAddDto ticketCategoryAddDto) {
        Program program = programMapper.selectById(ticketCategoryAddDto.getProgramId());
        if (Objects.isNull(program)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        TicketCategory ticketCategory = new TicketCategory();
        BeanUtil.copyProperties(ticketCategoryAddDto,ticketCategory);
        ticketCategory.setId(uidGenerator.getUid());
        ticketCategoryMapper.insert(ticketCategory);
        return ticketCategory.getId();
    }
    
    /**
     * 多级缓存查询票档列表。
     * Caffeine 本地缓存 → Redis → DB，逐级回退。
     *
     * @param programId 节目 ID
     * @param showTime 演出时间（用于计算缓存 TTL）
     * @return 票档 Vo 列表
     */
    public List<TicketCategoryVo> selectTicketCategoryListByProgramIdMultipleCache(Long programId, Date showTime){
        return localCacheTicketCategory.getCache(programId,key -> selectTicketCategoryListByProgramId(programId, 
                DateUtils.countBetweenSecond(DateUtils.now(),showTime), TimeUnit.SECONDS));
    }
    
    /**
     * 按节目 ID 查询票档列表（双检锁防缓存击穿）。
     * Redis 未命中时从 DB 加载并回填，remainNumber 不返回（实时余票由其他接口提供）。
     *
     * @param programId 节目 ID
     * @param expireTime 缓存过期时间
     * @param timeUnit 时间单位
     * @return 票档 Vo 列表
     */
    @ServiceLock(lockType= LockType.Read,name = TICKET_CATEGORY_LOCK,keys = {"#programId"})
    public List<TicketCategoryVo> selectTicketCategoryListByProgramId(Long programId,Long expireTime,TimeUnit timeUnit){
        List<TicketCategoryVo> ticketCategoryVoList = 
                redisCache.getValueIsList(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, 
                        programId), TicketCategoryVo.class);
        if (CollectionUtil.isNotEmpty(ticketCategoryVoList)) {
            return ticketCategoryVoList;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_TICKET_CATEGORY_LOCK, 
                new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            return redisCache.getValueIsList(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId),
                    TicketCategoryVo.class,
                    () -> {
                        LambdaQueryWrapper<TicketCategory> ticketCategoryLambdaQueryWrapper =
                                Wrappers.lambdaQuery(TicketCategory.class).eq(TicketCategory::getProgramId, programId);
                        List<TicketCategory> ticketCategoryList =
                                ticketCategoryMapper.selectList(ticketCategoryLambdaQueryWrapper);
                        return ticketCategoryList.stream().map(ticketCategory -> {
                            ticketCategory.setRemainNumber(null);
                            TicketCategoryVo ticketCategoryVo = new TicketCategoryVo();
                            BeanUtil.copyProperties(ticketCategory, ticketCategoryVo);
                            return ticketCategoryVo;
                        }).collect(Collectors.toList());
                    }, expireTime, timeUnit);
        }finally {
            lock.unlock();
        }
    }
    
    /**
     * 查询某个票价档位的余量分布。
     *
     * @ServiceLock(Read) 允许多个线程同时读，写时互斥。
     * 内部双层校验（读锁外一次、手动 ReentrantLock 内二次）：
     *   1) @ServiceLock 读锁保护 Redis 查询
     *   2) 如果 Redis 未命中，手动加 ReentrantLock 防缓存击穿，
     *      二次检查 Redis 后查 DB 回填
     *
     * Redis 存储结构：Hash，field = ticketCategoryId, value = remainNumber
     * key = PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION:{programId}:{ticketCategoryId}
     */
    @ServiceLock(lockType= LockType.Read,name = REMAIN_NUMBER_LOCK,keys = {"#programId","#ticketCategoryId"})
    public Map<String, Long> getRedisRemainNumberResolution(Long programId,Long ticketCategoryId){
        // 第一层：@ServiceLock(Read) 允许并发读，首次 Redis Hash 查询
        Map<String, Long> ticketCategoryRemainNumber =
                redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                        programId,ticketCategoryId), Long.class);
        
        if (CollectionUtil.isNotEmpty(ticketCategoryRemainNumber)) {
            return ticketCategoryRemainNumber;
        }
        // 第二层：手动 ReentrantLock + 二次 Redis 检查（双检锁模式防缓存击穿）
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_REMAIN_NUMBER_LOCK,
                new String[]{String.valueOf(programId),String.valueOf(ticketCategoryId)});
        lock.lock();
        try {
            // 双检：获取锁后再次查询 Redis，防止等待期间其他线程已回填
            ticketCategoryRemainNumber =
                    redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(
                            RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId,ticketCategoryId), Long.class);
            if (CollectionUtil.isNotEmpty(ticketCategoryRemainNumber)) {
                return ticketCategoryRemainNumber;
            }
            // 三级回退：从 DB 读取余量并写入 Redis Hash，供后续请求直接命中缓存
            LambdaQueryWrapper<TicketCategory> ticketCategoryLambdaQueryWrapper = Wrappers.lambdaQuery(TicketCategory.class)
                    .eq(TicketCategory::getProgramId, programId).eq(TicketCategory::getId,ticketCategoryId);
            List<TicketCategory> ticketCategoryList = ticketCategoryMapper.selectList(ticketCategoryLambdaQueryWrapper);
            Map<String, Long> map = ticketCategoryList.stream().collect(Collectors.toMap(t -> String.valueOf(t.getId()),
                    TicketCategory::getRemainNumber, (v1, v2) -> v2));
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                    programId,ticketCategoryId),map);
            return map;
        }finally {
            lock.unlock();
        }
    }
    
    /**
     * 查询票档详情。
     *
     * @param ticketCategoryDto 票档查询参数
     * @return 票档详情 Vo
     */
    public TicketCategoryDetailVo detail(TicketCategoryDto ticketCategoryDto) {
        TicketCategory ticketCategory = ticketCategoryMapper.selectById(ticketCategoryDto.getId());
        TicketCategoryDetailVo ticketCategoryDetailVo = new TicketCategoryDetailVo();
        BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailVo);
        return ticketCategoryDetailVo;
    }

    /**
     * 按节目 + 票档 ID 列表批量查询。
     *
     * @param ticketCategoryDto 票档批量查询参数
     * @return 票档详情 Vo 列表
     */
    public List<TicketCategoryDetailVo> selectList(TicketCategoryListDto ticketCategoryDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, ticketCategoryDto.getProgramId())
                .in(TicketCategory::getId, ticketCategoryDto.getTicketCategoryIdList()));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailVo ticketCategoryDetailVo = new TicketCategoryDetailVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailVo);
            return ticketCategoryDetailVo;
        }).collect(Collectors.toList());
    }

}
