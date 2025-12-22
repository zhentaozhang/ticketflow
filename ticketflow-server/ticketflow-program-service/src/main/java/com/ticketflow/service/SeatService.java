package com.ticketflow.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.dto.SeatAddDto;
import com.ticketflow.dto.SeatBatchAddDto;
import com.ticketflow.dto.SeatBatchRelateInfoAddDto;
import com.ticketflow.dto.SeatListDto;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.entity.Seat;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.enums.SeatType;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.SeatMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.lua.ProgramSeatCacheData;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.SeatRelateInfoVo;
import com.ticketflow.vo.SeatVo;
import com.ticketflow.vo.TicketCategoryVo;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketflow.core.DistributedLockConstants.GET_SEAT_LOCK;
import static com.ticketflow.core.DistributedLockConstants.SEAT_LOCK;

/**
 * 座位管理 Service（CRUD + Redis 缓存管理 + 数据同步）。
 * 核心职责：
 * 1. 座位数据的 DB 存储与缓存写入（add/batchAdd/relateInfo）
 * 2. 按节目 + 票档查询可用座位（getSeatVoByProgramAndTicket）
 * 3. 座位数据的程序化初始化（programCacheDataInit）
 * <p>
 * 数据写入路径：SeatService.add → DB insert → Redis HMSET → 余票初始化
 * 数据查询路径：getSeatVoByProgramAndTicket → Redis hvals → Java 合并（no_sold + lock + sold）
 */
@Service
public class SeatService extends ServiceImpl<SeatMapper, Seat> {

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private SeatMapper seatMapper;

    @Autowired
    private ProgramService programService;

    @Autowired
    private ProgramShowTimeService programShowTimeService;

    @Autowired
    private ServiceLockTool serviceLockTool;

    @Autowired
    private TicketCategoryService ticketCategoryService;

    @Autowired
    private ProgramSeatCacheData programSeatCacheData;

    /**
     * 添加单条座位记录。
     * 先校验行列是否已存在，通过后写入 DB。
     *
     * @param seatAddDto 座位添加参数
     * @return 座位 ID
     */
    public Long add(SeatAddDto seatAddDto) {
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper = Wrappers.lambdaQuery(Seat.class)
                .eq(Seat::getProgramId, seatAddDto.getProgramId())
                .eq(Seat::getRowCode, seatAddDto.getRowCode())
                .eq(Seat::getColCode, seatAddDto.getColCode());
        Seat seat = seatMapper.selectOne(seatLambdaQueryWrapper);
        if (Objects.nonNull(seat)) {
            throw new TicketFlowFrameException(BaseCode.SEAT_IS_EXIST);
        }
        seat = new Seat();
        BeanUtil.copyProperties(seatAddDto, seat);
        seat.setId(uidGenerator.getUid());
        seatMapper.insert(seat);
        return seat.getId();
    }

    /**
     * 按节目+票档查询座位，带两级防并发：
     *   @ServiceLock(Read) — 控制数据预热阶段的并发加载
     *   ReentrantLock(GET_SEAT_LOCK) — 防止同一节目+票档的缓存重建并发（double-check 模式）
     * 查询顺序：Redis 三区 hash（no_sold + lock + sold）→ 缓存未命中则 DB 加载
     * 写入缓存时按 sellStatus 拆分到三个 hash 中，TTL 由调用方控制
     */
    @ServiceLock(lockType = LockType.Read, name = SEAT_LOCK, keys = {"#programId", "#ticketCategoryId"})
    public List<SeatVo> selectSeatResolution(Long programId, Long ticketCategoryId, Long expireTime, TimeUnit timeUnit) {
        List<SeatVo> seatVoList = getSeatVoListByCacheResolution(programId, ticketCategoryId);
        if (CollectionUtil.isNotEmpty(seatVoList)) {
            return seatVoList;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_SEAT_LOCK, new String[]{String.valueOf(programId),
                String.valueOf(ticketCategoryId)});
        lock.lock();
        try {
            seatVoList = getSeatVoListByCacheResolution(programId, ticketCategoryId);
            if (CollectionUtil.isNotEmpty(seatVoList)) {
                return seatVoList;
            }
            LambdaQueryWrapper<Seat> seatLambdaQueryWrapper =
                    Wrappers.lambdaQuery(Seat.class).eq(Seat::getProgramId, programId)
                            .eq(Seat::getTicketCategoryId, ticketCategoryId);
            List<Seat> seats = seatMapper.selectList(seatLambdaQueryWrapper);
            for (Seat seat : seats) {
                SeatVo seatVo = new SeatVo();
                BeanUtil.copyProperties(seat, seatVo);
                seatVo.setSeatTypeName(SeatType.getMsg(seat.getSeatType()));
                seatVoList.add(seatVo);
            }
            Map<Integer, List<SeatVo>> seatMap = seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getSellStatus));
            List<SeatVo> noSoldSeatVoList = seatMap.get(SellStatus.NO_SOLD.getCode());
            List<SeatVo> lockSeatVoList = seatMap.get(SellStatus.LOCK.getCode());
            List<SeatVo> soldSeatVoList = seatMap.get(SellStatus.SOLD.getCode());
            if (CollectionUtil.isNotEmpty(noSoldSeatVoList)) {
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                                programId, ticketCategoryId), noSoldSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()), s -> s, (v1, v2) -> v2))
                        , expireTime, timeUnit);
            }
            if (CollectionUtil.isNotEmpty(lockSeatVoList)) {
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,
                                programId, ticketCategoryId), lockSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()), s -> s, (v1, v2) -> v2))
                        , expireTime, timeUnit);
            }
            if (CollectionUtil.isNotEmpty(soldSeatVoList)) {
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH,
                                programId, ticketCategoryId)
                        , soldSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()), s -> s, (v1, v2) -> v2))
                        , expireTime, timeUnit);
            }
            seatVoList = seatVoList.stream().sorted(Comparator.comparingInt(SeatVo::getRowCode)
                    .thenComparingInt(SeatVo::getColCode)).collect(Collectors.toList());
            return seatVoList;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从 Redis 三区 Hash（未售/锁定/已售）合并查询座位列表。
     * Lua 脚本按 sellStatus 分别读取后合并返回。
     *
     * @param programId 节目 ID
     * @param ticketCategoryId 票档 ID
     * @return 座位 Vo 列表
     */
    public List<SeatVo> getSeatVoListByCacheResolution(Long programId, Long ticketCategoryId) {
        List<String> keys = new ArrayList<>(4);
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        return programSeatCacheData.getData(keys, new String[]{});
    }

    /**
     * 获取座位关联信息（节目详情 + 演出时间 + 票档 + 票价区间座位 Map）。
     * 仅允许已开启选座的节目查询。
     *
     * @param seatListDto 座位查询参数
     * @return 座位关联信息 Vo
     */
    public SeatRelateInfoVo relateInfo(SeatListDto seatListDto) {
        SeatRelateInfoVo seatRelateInfoVo = new SeatRelateInfoVo();
        ProgramVo programVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, seatListDto.getProgramId()), ProgramVo.class);
        if (Objects.isNull(programVo)) {
            ProgramGetDto programGetDto = new ProgramGetDto();
            programGetDto.setId(seatListDto.getProgramId());
            programVo = programService.detail(programGetDto);
        }
        ProgramShowTime programShowTime = programShowTimeService.selectProgramShowTimeByProgramId(seatListDto.getProgramId());
        List<TicketCategoryVo> ticketCategoryVoList = ticketCategoryService
                .selectTicketCategoryListByProgramIdMultipleCache(programVo.getId(), programShowTime.getShowTime());

        List<SeatVo> seatVos = new ArrayList<>();
        for (TicketCategoryVo ticketCategoryVo : ticketCategoryVoList) {
            seatVos.addAll(selectSeatResolution(seatListDto.getProgramId(), ticketCategoryVo.getId(),
                    DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS));
        }

        if (programVo.getPermitChooseSeat().equals(BusinessStatus.NO.getCode())) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_ALLOW_CHOOSE_SEAT);
        }

        Map<String, List<SeatVo>> seatVoMap =
                seatVos.stream().collect(Collectors.groupingBy(seatVo -> seatVo.getPrice().toString()));
        seatRelateInfoVo.setProgramId(programVo.getId());
        seatRelateInfoVo.setPlace(programVo.getPlace());
        seatRelateInfoVo.setShowTime(programShowTime.getShowTime());
        seatRelateInfoVo.setShowWeekTime(programShowTime.getShowWeekTime());
        seatRelateInfoVo.setPriceList(seatVoMap.keySet().stream().sorted().collect(Collectors.toList()));
        seatRelateInfoVo.setSeatVoMap(seatVoMap);
        return seatRelateInfoVo;
    }

    /**
     * 批量添加座位（事务保护）。
     * 按票档分组，逐行逐列生成座位记录，初始销售状态为未售。
     *
     * @param seatBatchAddDto 批量座位添加参数
     * @return true
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean batchAdd(SeatBatchAddDto seatBatchAddDto) {
        Long programId = seatBatchAddDto.getProgramId();
        List<SeatBatchRelateInfoAddDto> seatBatchRelateInfoAddDtoList = seatBatchAddDto.getSeatBatchRelateInfoAddDtoList();


        int rowIndex = 0;
        for (SeatBatchRelateInfoAddDto seatBatchRelateInfoAddDto : seatBatchRelateInfoAddDtoList) {
            Long ticketCategoryId = seatBatchRelateInfoAddDto.getTicketCategoryId();
            BigDecimal price = seatBatchRelateInfoAddDto.getPrice();
            Integer count = seatBatchRelateInfoAddDto.getCount();

            int colCount = seatBatchRelateInfoAddDto.getColCount();
            int rowCount = count / colCount;

            for (int i = 1; i <= rowCount; i++) {
                rowIndex++;
                for (int j = 1; j <= colCount; j++) {
                    Seat seat = new Seat();
                    seat.setProgramId(programId);
                    seat.setTicketCategoryId(ticketCategoryId);
                    seat.setRowCode(rowIndex);
                    seat.setColCode(j);
                    seat.setSeatType(1);
                    seat.setPrice(price);
                    seat.setSellStatus(SellStatus.NO_SOLD.getCode());
                    seatMapper.insert(seat);
                }
            }
        }

        return true;
    }
}
