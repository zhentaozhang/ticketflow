package com.ticketflow.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramManageDto;
import com.ticketflow.dto.SeatPageManageDto;
import com.ticketflow.entity.Seat;
import com.ticketflow.entity.TicketCategory;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.mapper.SeatMapper;
import com.ticketflow.mapper.TicketCategoryMapper;
import com.ticketflow.page.PageUtil;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.SeatManageVo;
import com.ticketflow.vo.TicketCategoryDbManageVo;
import com.ticketflow.vo.TicketCategoryDetailManageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 节目后台管理服务。
 * 票档列表查询、票价管理、座位列表分页。
 * 直接查询 DB，不经过 Redis 缓存，用于管理后台展示真实数据
 */
@Slf4j
@Service
public class ProgramManageService  {
    
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    
    @Autowired
    private SeatMapper seatMapper;
    
    @Autowired
    private RedisCache redisCache;
    
    
    /**
     * 查询票档管理列表（含 DB + Redis 双端余量对照）。
     * 用于管理后台排查缓存一致性。
     *
     * @param programManageDto 节目管理查询参数
     * @return 票档管理 Vo 列表
     */
    public List<TicketCategoryDetailManageVo> ticketCategoryList(ProgramManageDto programManageDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programManageDto.getProgramId())
                .orderByAsc(TicketCategory::getPrice));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailManageVo ticketCategoryDetailManageVo = new TicketCategoryDetailManageVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailManageVo);
            ticketCategoryDetailManageVo.setDbRemainNumber(ticketCategory.getRemainNumber());
            //Key:票档id，value:节目id
            Map<String, Long> ticketCategoryRemainNumber =
                    redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                            ticketCategory.getProgramId(),ticketCategory.getId()), Long.class);
            if (CollectionUtil.isNotEmpty(ticketCategoryRemainNumber)) {
                ticketCategoryDetailManageVo.setRedisRemainNumber(ticketCategoryRemainNumber.get(ticketCategory.getId().toString()));
            }
            return ticketCategoryDetailManageVo;
        }).collect(Collectors.toList());
    }
    
    /**
     * 查询票档管理列表（仅 DB 数据，不查 Redis）。
     * 用于后台真实数据导出。
     *
     * @param programManageDto 节目管理查询参数
     * @return 票档 DB 管理 Vo 列表
     */
    public List<TicketCategoryDbManageVo> dbTicketCategoryList(ProgramManageDto programManageDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programManageDto.getProgramId())
                .orderByAsc(TicketCategory::getPrice));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDbManageVo ticketCategoryDbManageVo = new TicketCategoryDbManageVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDbManageVo);
            return ticketCategoryDbManageVo;
        }).toList();
    }
    
    /**
     * 分页查询座位管理列表（DB + Redis 状态对照）。
     * 从 DB 分页后逐票档查询 Redis 三区 Hash 获取实时状态，
     * 返回 DB 状态与 Redis 状态对照，便于排查数据不一致。
     *
     * @param seatPageManageDto 座位分页查询参数
     * @return 座位管理分页数据
     */
    public IPage<SeatManageVo> seatPage(SeatPageManageDto seatPageManageDto) {
        IPage<SeatManageVo> seatManageVoPage = new Page<>(seatPageManageDto.getPageNumber(), seatPageManageDto.getPageSize());
        //查询前5分钟订单节目管理表
        IPage<Seat> seatPage =
                seatMapper.selectPage(PageUtil.getPageParams(seatPageManageDto.getPageNumber(),
                        seatPageManageDto.getPageSize()),Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId, seatPageManageDto.getProgramId())
                        .eq(Objects.nonNull(seatPageManageDto.getTicketCategoryId()),Seat::getTicketCategoryId, seatPageManageDto.getTicketCategoryId()));
        if (CollectionUtil.isEmpty(seatPage.getRecords())) {
            return seatManageVoPage;
        }
        // 按票档 ID 分组，每个票档分别查询其三个 Redis Hash 区域（未售/锁定/已售）
        Map<Long, List<Seat>> seatMap = seatPage.getRecords().stream().collect(Collectors.groupingBy(Seat::getTicketCategoryId));
        // Redis 中座位数据合并到同一 Map（key = 座位 ID，覆盖已售状态）
        Map<Long,Seat> redisSeatMap = new HashMap<>(seatPage.getRecords().size());
        for (Entry<Long, List<Seat>> entry : seatMap.entrySet()) {
            Long ticketCategoryId = entry.getKey();
            List<String> seatIdList = entry.getValue().stream().map(Seat::getId).map(String::valueOf).toList();
            // 三个 Redis Hash：PROGRAM_SEAT_NO_SOLD / LOCK / SOLD —— 分别存储不同状态的座位数据
            List<Seat> noSoldSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            List<Seat> lockSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            List<Seat> soldSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            // 合并三个 Hash 结果（后写入的覆盖前者，正确反映座位的当前 Redis 状态）
            for (Seat seat : noSoldSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
            for (Seat seat : lockSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
            for (Seat seat : soldSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
        }
        
        // DB 状态 vs Redis 状态对照展示：dbSellStatus 为数据库记录，redisSellStatus 为缓存实时状态
        List<SeatManageVo> seatManageVoList = new ArrayList<>();
        for (Seat seat : seatPage.getRecords()) {
            SeatManageVo seatManageVo = new SeatManageVo();
            BeanUtil.copyProperties(seat,seatManageVo);
            seatManageVo.setDbSellStatus(seat.getSellStatus());
            seatManageVo.setDbSellStatusName(SellStatus.getMsg(seat.getSellStatus()));
            Seat redisSeat = redisSeatMap.get(seat.getId());
            if (Objects.nonNull(redisSeat)) {
                seatManageVo.setRedisSellStatus(redisSeat.getSellStatus());
                seatManageVo.setRedisSellStatusName(Optional.ofNullable(SellStatus.getMsg(redisSeat.getSellStatus()))
                        .filter(StringUtil::isNotEmpty).orElse("无"));
            }
            seatManageVoList.add(seatManageVo);
        }
        BeanUtils.copyProperties(seatPage, seatManageVoPage);
        seatManageVoPage.setRecords(seatManageVoList);
        return seatManageVoPage;
    }
}
