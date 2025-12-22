package com.ticketflow.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramShowTimeAddDto;
import com.ticketflow.entity.Program;
import com.ticketflow.entity.ProgramGroup;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ProgramGroupMapper;
import com.ticketflow.mapper.ProgramMapper;
import com.ticketflow.mapper.ProgramShowTimeMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.cache.local.LocalCacheProgramShowTime;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.core.DistributedLockConstants.GET_PROGRAM_SHOW_TIME_LOCK;
import static com.ticketflow.core.DistributedLockConstants.PROGRAM_SHOW_TIME_LOCK;

/**
 * 节目演出时间服务。
 * 管理每场节目的日期时间、缓存双删策略，
 * 被 ProgramService 在查询和创建节目时调用
 */
@Service
public class ProgramShowTimeService extends ServiceImpl<ProgramShowTimeMapper, ProgramShowTime> {

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ProgramMapper programMapper;

    @Autowired
    private ProgramShowTimeMapper programShowTimeMapper;

    @Autowired
    private ProgramGroupMapper programGroupMapper;

    @Autowired
    private ServiceLockTool serviceLockTool;

    @Autowired
    private LocalCacheProgramShowTime localCacheProgramShowTime;


    /**
     * 新增节目演出时间记录。
     *
     * @param programShowTimeAddDto 演出时间新增参数
     * @return 演出时间记录 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long add(ProgramShowTimeAddDto programShowTimeAddDto) {
        ProgramShowTime programShowTime = new ProgramShowTime();
        BeanUtil.copyProperties(programShowTimeAddDto, programShowTime);
        programShowTime.setId(uidGenerator.getUid());
        programShowTimeMapper.insert(programShowTime);
        return programShowTime.getId();
    }

    /**
     * 多级缓存查询演出时间。
     * Caffeine 本地缓存 → Redis，未命中时查 DB 并回填。
     *
     * @param programId 节目 ID
     * @return 演出时间实体
     */
    public ProgramShowTime selectProgramShowTimeByProgramIdMultipleCache(Long programId) {
        return localCacheProgramShowTime.getCache(RedisKeyBuild.createRedisKey
                        (RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey(),
                key -> selectProgramShowTimeByProgramId(programId));
    }

    /**
     * 轻量级多级缓存查询（仅查询，不触发 DB 加载）。
     * 仅查本地缓存 + Redis，不会回填缺失数据。
     *
     * @param programId 节目 ID
     * @return 演出时间实体，缓存未命中时返回 null
     */
    public ProgramShowTime simpleSelectProgramShowTimeByProgramIdMultipleCache(Long programId) {
        ProgramShowTime programShowTimeCache = localCacheProgramShowTime.getCache(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey());
        if (Objects.nonNull(programShowTimeCache)) {
            return programShowTimeCache;
        }
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,
                programId), ProgramShowTime.class);
    }

    /**
     * 按节目 ID 查询演出时间（双检锁防缓存击穿）。
     * Redis 未命中时从 DB 加载，以节目距离演出时长为 TTL 回填缓存。
     *
     * @param programId 节目 ID
     * @return 演出时间实体
     */
    @ServiceLock(lockType = LockType.Read, name = PROGRAM_SHOW_TIME_LOCK, keys = {"#programId"})
    public ProgramShowTime selectProgramShowTimeByProgramId(Long programId) {
        ProgramShowTime programShowTime = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,
                programId), ProgramShowTime.class);
        if (Objects.nonNull(programShowTime)) {
            return programShowTime;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_SHOW_TIME_LOCK,
                new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            programShowTime = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,
                    programId), ProgramShowTime.class);
            if (Objects.isNull(programShowTime)) {
                LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                        Wrappers.lambdaQuery(ProgramShowTime.class).eq(ProgramShowTime::getProgramId, programId);
                programShowTime = Optional.ofNullable(programShowTimeMapper.selectOne(programShowTimeLambdaQueryWrapper))
                        .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST));
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId), programShowTime
                        , DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);
            }
            return programShowTime;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 定时滚动更新演出时间。
     * 将已过期或即将过期的演出时间以月为步长向后推进，
     * 同步更新节目组的最近演出时间（取组内最早场次）。
     *
     * @return 被更新的节目 ID 集合（用于后续缓存清理）
     */
    @Transactional(rollbackFor = Exception.class)
    public Set<Long> renewal() {
        Set<Long> programIdSet = new HashSet<>();
        // 查询演出时间早于当前时间 2 天前的记录（即将过期或已过期的场次）
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                Wrappers.lambdaQuery(ProgramShowTime.class).
                        le(ProgramShowTime::getShowTime, DateUtils.addDay(DateUtils.now(), 2));
        List<ProgramShowTime> programShowTimes = programShowTimeMapper.selectList(programShowTimeLambdaQueryWrapper);

        List<ProgramShowTime> newProgramShowTimes = new ArrayList<>(programShowTimes.size());

        for (ProgramShowTime programShowTime : programShowTimes) {
            programIdSet.add(programShowTime.getProgramId());
            // 以月为步长向前滚动，直到新的演出时间大于当前时间（持续上演的节目，每月更新场次）
            Date oldShowTime = programShowTime.getShowTime();
            Date newShowTime = DateUtils.addMonth(oldShowTime, 1);
            Date nowDateTime = DateUtils.now();
            // 若已过期超过一个月，则连续推进多次直到回到未来
            while (newShowTime.before(nowDateTime)) {
                newShowTime = DateUtils.addMonth(newShowTime, 1);
            }
            // 更新数据库中的 showTime / showDayTime / showWeekTime（showWeekTime 用于前端星期展示）
            Date newShowDayTime = DateUtils.parseDateTime(DateUtils.formatDate(newShowTime) + " 00:00:00");
            ProgramShowTime updateProgramShowTime = new ProgramShowTime();
            updateProgramShowTime.setShowTime(newShowTime);
            updateProgramShowTime.setShowDayTime(newShowDayTime);
            updateProgramShowTime.setShowWeekTime(DateUtils.getWeekStr(newShowTime));
            LambdaUpdateWrapper<ProgramShowTime> programShowTimeLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(ProgramShowTime.class)
                            .eq(ProgramShowTime::getProgramId, programShowTime.getProgramId())
                            .eq(ProgramShowTime::getId, programShowTime.getId());
            programShowTimeMapper.update(updateProgramShowTime, programShowTimeLambdaUpdateWrapper);

            // 保存更新后的数据，用于后续计算节目组最近演出时间
            ProgramShowTime newProgramShowTime = new ProgramShowTime();
            newProgramShowTime.setProgramId(programShowTime.getProgramId());
            newProgramShowTime.setShowTime(newShowTime);
            newProgramShowTimes.add(newProgramShowTime);
        }
        // 节目组最近演出时间更新：取该组下所有节目中最小的（最近的）演出时间
        Map<Long, Date> programGroupMap = new HashMap<>(newProgramShowTimes.size());
        for (ProgramShowTime newProgramShowTime : newProgramShowTimes) {
            Program program = programMapper.selectById(newProgramShowTime.getProgramId());
            if (Objects.isNull(program)) {
                continue;
            }
            Long programGroupId = program.getProgramGroupId();
            Date showTime = programGroupMap.get(programGroupId);
            if (Objects.isNull(showTime)) {
                programGroupMap.put(programGroupId, newProgramShowTime.getShowTime());
            } else {
                // 取当前组中最早的一场时间作为 recentShowTime（用于列表排序展示）
                if (DateUtil.compare(newProgramShowTime.getShowTime(), showTime) < 0) {
                    programGroupMap.put(programGroupId, newProgramShowTime.getShowTime());
                }
            }
        }
        if (CollectionUtil.isNotEmpty(programGroupMap)) {
            programGroupMap.forEach((k, v) -> {
                ProgramGroup programGroup = new ProgramGroup();
                programGroup.setRecentShowTime(v);

                LambdaUpdateWrapper<ProgramGroup> programGroupLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(ProgramGroup.class)
                                .eq(ProgramGroup::getId, k);
                programGroupMapper.update(programGroup, programGroupLambdaUpdateWrapper);
            });
        }
        return programIdSet;
    }
}
