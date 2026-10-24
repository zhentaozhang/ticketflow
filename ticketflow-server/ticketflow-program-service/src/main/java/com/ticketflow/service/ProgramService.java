package com.ticketflow.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.RedisStreamPushHandler;
import com.ticketflow.client.BaseDataClient;
import com.ticketflow.client.OrderClient;
import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.service.cache.CacheTtlUtil;
import com.ticketflow.dto.AccountOrderCountDto;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.dto.ProgramAddDto;
import com.ticketflow.dto.ProgramDataPreheatDto;
import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.dto.ProgramInvalidDto;
import com.ticketflow.dto.ProgramListDto;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.ProgramPageListDto;
import com.ticketflow.dto.ProgramRecommendListDto;
import com.ticketflow.dto.ProgramResetExecuteDto;
import com.ticketflow.dto.ProgramSearchDto;
import com.ticketflow.dto.ReduceRemainNumberDto;
import com.ticketflow.dto.SeatSellStatusDto;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.entity.Program;
import com.ticketflow.entity.ProgramCategory;
import com.ticketflow.entity.ProgramGroup;
import com.ticketflow.entity.ProgramJoinShowTime;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.entity.Seat;
import com.ticketflow.entity.TicketCategory;
import com.ticketflow.entity.TicketCategoryAggregate;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.handler.BloomFilterHandler;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.mapper.ProgramCategoryMapper;
import com.ticketflow.mapper.ProgramGroupMapper;
import com.ticketflow.mapper.ProgramMapper;
import com.ticketflow.mapper.ProgramShowTimeMapper;
import com.ticketflow.mapper.SeatMapper;
import com.ticketflow.mapper.TicketCategoryMapper;
import com.ticketflow.page.PageUtil;
import com.ticketflow.page.PageVo;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.service.cache.local.LocalCacheProgram;
import com.ticketflow.service.cache.local.LocalCacheProgramCategory;
import com.ticketflow.service.cache.local.LocalCacheProgramGroup;
import com.ticketflow.service.cache.local.LocalCacheProgramShowTime;
import com.ticketflow.service.cache.local.LocalCacheTicketCategory;
import com.ticketflow.service.constant.ProgramTimeType;
import com.ticketflow.service.es.ProgramEs;
import com.ticketflow.service.lua.ProgramDelCacheData;
import com.ticketflow.service.tool.TokenExpireManager;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.AccountOrderCountVo;
import com.ticketflow.vo.AreaVo;
import com.ticketflow.vo.ProgramGroupVo;
import com.ticketflow.vo.ProgramHomeVo;
import com.ticketflow.vo.ProgramListVo;
import com.ticketflow.vo.ProgramSimpleInfoVo;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.SeatSellStatusVo;
import com.ticketflow.vo.TicketCategoryVo;
import com.ticketflow.vo.TicketUserVo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.CODE;
import static com.ticketflow.constant.Constant.USER_ID;
import static com.ticketflow.core.DistributedLockConstants.GET_PROGRAM_GROUP_LOCK;
import static com.ticketflow.core.DistributedLockConstants.GET_PROGRAM_LOCK;
import static com.ticketflow.core.RepeatExecuteLimitConstants.PAY_OR_CANCEL_PROGRAM_ORDER;
import static com.ticketflow.core.RepeatExecuteLimitConstants.REDUCE_REMAIN_NUMBER;
import static com.ticketflow.util.DateUtils.FORMAT_DATE;

/**
 * 节目服务核心逻辑（~1000行），覆盖完整生命周期：
 * CRUD：创建（含多级缓存预热）、分页搜索、详情查询
 * 下单路径：seatLock → 余票扣减 → 投递订单
 * 支付/取消回调：operateProgramData → 更新 DB+Redis+Lua 原子释放
 * 管理：后台重置（缓存清理+数据回滚）、预热调度
 * 对内：Feign 接口的座位锁定、余票扣减、对账记录维护
 */
@Slf4j
@Service
public class ProgramService extends ServiceImpl<ProgramMapper, Program> {

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private ProgramMapper programMapper;

    @Autowired
    private ProgramGroupMapper programGroupMapper;

    @Autowired
    private ProgramShowTimeMapper programShowTimeMapper;

    @Autowired
    private ProgramCategoryMapper programCategoryMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private SeatMapper seatMapper;

    @Autowired
    private BaseDataClient baseDataClient;

    @Autowired
    private UserClient userClient;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private RedisCache redisCache;

    @Lazy
    @Autowired
    private ProgramService programService;

    @Autowired
    private ProgramShowTimeService programShowTimeService;

    @Autowired
    private TicketCategoryService ticketCategoryService;

    @Autowired
    private ProgramCategoryService programCategoryService;

    @Autowired
    private ProgramEs programEs;

    @Autowired
    private ServiceLockTool serviceLockTool;

    @Autowired
    private RedisStreamPushHandler redisStreamPushHandler;

    @Autowired
    private LocalCacheProgram localCacheProgram;

    @Autowired
    private LocalCacheProgramGroup localCacheProgramGroup;

    @Autowired
    private LocalCacheProgramCategory localCacheProgramCategory;

    @Autowired
    private LocalCacheProgramShowTime localCacheProgramShowTime;

    @Autowired
    private LocalCacheTicketCategory localCacheTicketCategory;

    @Autowired
    private CompositeContainer compositeContainer;

    @Autowired
    private TokenExpireManager tokenExpireManager;

    @Autowired
    private ProgramDelCacheData programDelCacheData;

    @Autowired
    private SeatService seatService;

    @Autowired
    private BloomFilterHandler bloomFilterHandler;

    /**
     * 添加节目
     *
     * @param programAddDto 添加节目数据的入参
     * @return 添加节目后的id
     *
     */
    public Long add(ProgramAddDto programAddDto) {
        Program program = new Program();
        BeanUtil.copyProperties(programAddDto, program);
        program.setId(uidGenerator.getUid());
        programMapper.insert(program);
        if (Objects.nonNull(bloomFilterHandler)) {
            bloomFilterHandler.add(String.valueOf(program.getId()));
        }
        return program.getId();
    }

    /**
     * 搜索
     *
     * @param programSearchDto 搜索节目数据的入参
     * @return 执行后的结果
     *
     */
    public PageVo<ProgramListVo> search(ProgramSearchDto programSearchDto) {
        //将入参的参数进行具体的组装
        setQueryTime(programSearchDto);
        try {
            //使用elasticsearch查询
            PageVo<ProgramListVo> pageVo = programEs.search(programSearchDto);
            // 只要 ES 正常响应（即使结果为空列表，说明无匹配节目），即直接返回，避免冷门词穿透回源 MySQL
            if (pageVo != null) {
                return pageVo;
            }
        } catch (Exception e) {
            log.error("ES 检索异常，降级走 DB 兜底查询 programSearchDto : {}", JSON.toJSONString(programSearchDto), e);
        }
        // ES 不可用或抛出异常时走 DB 兜底（DB 侧无搜索词全文检索，仅按分类/时间过滤）
        return dbSelectPage(programSearchDto);
    }

    /**
     * 查询主页信息
     *
     * @param programListDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public List<ProgramHomeVo> selectHomeList(ProgramListDto programListDto) {
        try {
            List<ProgramHomeVo> programHomeVoList = programEs.selectHomeList(programListDto);
            if (CollectionUtil.isNotEmpty(programHomeVoList)) {
                return programHomeVoList;
            }
        } catch (Exception e) {
            log.error("ES 查询主页异常，降级走 DB 兜底查询 programListDto : {}", JSON.toJSONString(programListDto), e);
        }
        return dbSelectHomeList(programListDto);
    }

    /**
     * 从数据库查询主页节目列表，按父分类分组组装首页数据。
     * 包含分类名称、场次时间、票价区间（最低~最高）。
     * 在 ES 查询无结果时作为降级方案调用。
     *
     * @param programPageListDto 查询节目数据的入参
     * @return 按父分类分组的主页数据
     */
    private List<ProgramHomeVo> dbSelectHomeList(ProgramListDto programPageListDto) {
        List<ProgramHomeVo> programHomeVoList = new ArrayList<>();
        // 1. 查询父分类名称映射
        Map<Long, String> programCategoryMap = selectProgramCategoryMap(programPageListDto.getParentProgramCategoryIds());

        // 2. 查询该分类下的节目列表
        List<Program> programList = programMapper.selectHomeList(programPageListDto);
        if (CollectionUtil.isEmpty(programList)) {
            return programHomeVoList;
        }

        List<Long> programIdList = programList.stream().map(Program::getId).collect(Collectors.toList());
        // 3. 批量查询场次时间
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper = Wrappers.lambdaQuery(ProgramShowTime.class)
                .in(ProgramShowTime::getProgramId, programIdList);
        List<ProgramShowTime> programShowTimeList = programShowTimeMapper.selectList(programShowTimeLambdaQueryWrapper);
        Map<Long, List<ProgramShowTime>> programShowTimeMap =
                programShowTimeList.stream().collect(Collectors.groupingBy(ProgramShowTime::getProgramId));

        // 4. 批量查询票价区间
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = selectTicketCategorieMap(programIdList);

        // 5. 按父分类分组，组装首页每个分类下的节目列表
        Map<Long, List<Program>> programMap = programList.stream()
                .collect(Collectors.groupingBy(Program::getParentProgramCategoryId));

        for (Entry<Long, List<Program>> programEntry : programMap.entrySet()) {
            Long key = programEntry.getKey();
            List<Program> value = programEntry.getValue();
            List<ProgramListVo> programListVoList = new ArrayList<>();
            for (Program program : value) {
                ProgramListVo programListVo = new ProgramListVo();
                BeanUtil.copyProperties(program, programListVo);

                programListVo.setShowTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowTime)
                        .orElse(null));
                programListVo.setShowDayTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowDayTime)
                        .orElse(null));
                programListVo.setShowWeekTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowWeekTime)
                        .orElse(null));

                programListVo.setMaxPrice(Optional.ofNullable(ticketCategorieMap.get(program.getId()))
                        .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
                programListVo.setMinPrice(Optional.ofNullable(ticketCategorieMap.get(program.getId()))
                        .map(TicketCategoryAggregate::getMinPrice).orElse(null));
                programListVoList.add(programListVo);
            }
            ProgramHomeVo programHomeVo = new ProgramHomeVo();
            programHomeVo.setCategoryName(programCategoryMap.get(key));
            programHomeVo.setCategoryId(key);
            programHomeVo.setProgramListVoList(programListVoList);
            programHomeVoList.add(programHomeVo);
        }
        return programHomeVoList;
    }

    /**
     * 根据 timeType（今日/明日/本周/本月/自定义）设置查询起止时间。
     * CALENDAR 模式下要求入参必须携带 startDateTime 和 endDateTime。
     *
     * @param programPageListDto 节目数据的入参
     */
    public void setQueryTime(ProgramPageListDto programPageListDto) {
        switch (programPageListDto.getTimeType()) {
            case ProgramTimeType.TODAY:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.now(FORMAT_DATE));
                break;
            case ProgramTimeType.TOMORROW:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addDay(DateUtils.now(FORMAT_DATE), 1));
                break;
            case ProgramTimeType.WEEK:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addWeek(DateUtils.now(FORMAT_DATE), 1));
                break;
            case ProgramTimeType.MONTH:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addMonth(DateUtils.now(FORMAT_DATE), 1));
                break;
            case ProgramTimeType.CALENDAR:
                if (Objects.isNull(programPageListDto.getStartDateTime())) {
                    throw new TicketFlowFrameException(BaseCode.START_DATE_TIME_NOT_EXIST);
                }
                if (Objects.isNull(programPageListDto.getEndDateTime())) {
                    throw new TicketFlowFrameException(BaseCode.END_DATE_TIME_NOT_EXIST);
                }
                break;
            default:
                programPageListDto.setStartDateTime(null);
                programPageListDto.setEndDateTime(null);
                break;
        }
    }

    /**
     * 查询分类列表（数据库查询）
     *
     * @param programPageListDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public PageVo<ProgramListVo> selectPage(ProgramPageListDto programPageListDto) {
        //处理时间范围参数
        setQueryTime(programPageListDto);
        //使用elasticsearch查询
        PageVo<ProgramListVo> pageVo = programEs.selectPage(programPageListDto);
        if (pageVo != null && CollectionUtil.isNotEmpty(pageVo.getList())) {
            return pageVo;
        }
        return dbSelectPage(programPageListDto);
    }

    /**
     * 推荐列表
     *
     * @param programRecommendListDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public List<ProgramListVo> recommendList(ProgramRecommendListDto programRecommendListDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_RECOMMEND_CHECK.getValue(), programRecommendListDto);
        List<ProgramListVo> programListVoList = programEs.recommendList(programRecommendListDto);
        if (CollectionUtil.isNotEmpty(programListVoList)) {
            return programListVoList;
        }
        //ES 无数据或不可用时走 DB 兜底（按热度取 10 条，不保留随机排序与排除逻辑）
        ProgramPageListDto programPageListDto = new ProgramPageListDto();
        BeanUtil.copyProperties(programRecommendListDto, programPageListDto);
        programPageListDto.setType(2);
        programPageListDto.setPageNumber(1);
        programPageListDto.setPageSize(10);
        return dbSelectPage(programPageListDto).getList();
    }

    /**
     * 查询分类信息（数据库查询）
     *
     * @param programPageListDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public PageVo<ProgramListVo> dbSelectPage(ProgramPageListDto programPageListDto) {
        // 1. 分页查询节目+场次关联数据
        IPage<ProgramJoinShowTime> iPage =
                programMapper.selectPage(PageUtil.getPageParams(programPageListDto), programPageListDto);
        if (CollectionUtil.isEmpty(iPage.getRecords())) {
            return new PageVo<>(iPage.getCurrent(), iPage.getSize(), iPage.getTotal(), new ArrayList<>());
        }
        // 2. 批量查询分类名称
        Set<Long> programCategoryIdList =
                iPage.getRecords().stream().map(Program::getProgramCategoryId).collect(Collectors.toSet());
        Map<Long, String> programCategoryMap = selectProgramCategoryMap(programCategoryIdList);

        // 3. 批量查询票价区间
        List<Long> programIdList = iPage.getRecords().stream().map(Program::getId).collect(Collectors.toList());
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = selectTicketCategorieMap(programIdList);

        // 4. RPC 查询地区名称
        Map<Long, String> tempAreaMap = new HashMap<>(64);
        AreaSelectDto areaSelectDto = new AreaSelectDto();
        areaSelectDto.setIdList(iPage.getRecords().stream().map(Program::getAreaId).distinct().collect(Collectors.toList()));
        ApiResponse<List<AreaVo>> areaResponse = baseDataClient.selectByIdList(areaSelectDto);
        if (Objects.equals(areaResponse.getCode(), ApiResponse.ok().getCode())) {
            if (CollectionUtil.isNotEmpty(areaResponse.getData())) {
                tempAreaMap = areaResponse.getData().stream()
                        .collect(Collectors.toMap(AreaVo::getId, AreaVo::getName, (v1, v2) -> v2));
            }
        } else {
            log.error("base-data selectByIdList rpc error areaResponse:{}", JSON.toJSONString(areaResponse));
        }
        Map<Long, String> areaMap = tempAreaMap;

        return PageUtil.convertPage(iPage, programJoinShowTime -> {
            ProgramListVo programListVo = new ProgramListVo();
            BeanUtil.copyProperties(programJoinShowTime, programListVo);

            programListVo.setAreaName(areaMap.get(programJoinShowTime.getAreaId()));
            programListVo.setProgramCategoryName(programCategoryMap.get(programJoinShowTime.getProgramCategoryId()));
            programListVo.setMinPrice(Optional.ofNullable(ticketCategorieMap.get(programJoinShowTime.getId()))
                    .map(TicketCategoryAggregate::getMinPrice).orElse(null));
            programListVo.setMaxPrice(Optional.ofNullable(ticketCategorieMap.get(programJoinShowTime.getId()))
                    .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
            return programListVo;
        });
    }

    /**
     * 查询节目详情
     *
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public ProgramVo detail(ProgramGetDto programGetDto) {
        String cacheKey = RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programGetDto.getId()).getRelKey();
        ProgramVo cached = localCacheProgram.getCache(cacheKey);
        // 快速通道：本地已有缓存时证明该节目合法存在且有效，跳过远程布隆过滤器网络往返
        if (Objects.isNull(cached)) {
            // 慢速通道：未命中本地缓存（首次访问或冷数据），校验布隆过滤器防止非法 ID 穿透至 Redis 和 DB
            compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(), programGetDto);
        }
        return getDetailV2(programGetDto);
    }

    /**
     * 查询节目详情V2执行
     *
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public ProgramVo getDetailV2(ProgramGetDto programGetDto) {
        // 1. 多级缓存查询场次时间 + 节目详情
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programGetDto.getId());

        ProgramVo cachedProgramVo = programService.getByIdMultipleCache(programGetDto.getId(), programShowTime.getShowTime());

        // 深层隔离克隆：独立实例化主 VO，保护 Caffeine 堆内数据不可变
        ProgramVo programVo = new ProgramVo();
        BeanUtil.copyProperties(cachedProgramVo, programVo);

        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());

        // 2. 多级缓存查询节目分组
        ProgramGroupVo programGroupVo = programService.getProgramGroupMultipleCache(programVo.getProgramGroupId());
        programVo.setProgramGroupVo(programGroupVo);

        // 3. 预热购票人列表和下单数量
        preloadTicketUserList(programVo.getHighHeat());

        preloadAccountOrderCount(programVo.getId());

        // 4. 多级缓存查询分类名称
        ProgramCategory programCategory = getProgramCategoryMultipleCache(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategoryMultipleCache(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }

        // 5. 多级缓存查询票档列表，独立实例化集合，防止外部修改集合结构
        List<TicketCategoryVo> ticketCategoryVoList = ticketCategoryService
                .selectTicketCategoryListByProgramIdMultipleCache(programVo.getId(), programShowTime.getShowTime());
        programVo.setTicketCategoryVoList(ticketCategoryVoList != null ? new ArrayList<>(ticketCategoryVoList) : null);

        return programVo;
    }

    /**
     * 查询节目表详情执行（多级）
     *
     * @param programId 节目id
     * @param showTime  节目演出时间
     * @return 执行后的结果
     *
     */
    public ProgramVo getByIdMultipleCache(Long programId, Date showTime) {
        return localCacheProgram.getCache(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey(),
                key -> {    // 加载函数，只有 miss 才执行
                    log.info("查询节目详情 从本地缓存没有查询到 节目id : {}", programId);
                    ProgramVo programVo = getById(programId, CacheTtlUtil.calculateExpireSeconds(showTime),
                            TimeUnit.SECONDS);
                    programVo.setShowTime(showTime);
                    return programVo;
                });
    }

    /**
     * 两级缓存查询节目详情：本地 Caffeine → Redis。
     * 不查 DB，不涉及锁，适合无需强一致性的快速读取场景。
     *
     * @param programId 节目 id
     * @return 节目详情，缓存均不存在时返回 null
     */
    public ProgramVo simpleGetByIdMultipleCache(Long programId) {
        ProgramVo programVoCache = localCacheProgram.getCache(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM,
                programId).getRelKey());
        if (Objects.nonNull(programVoCache)) {
            return programVoCache;
        }
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId), ProgramVo.class);
    }

    /**
     * 两级缓存查询节目详情 + 场次信息（合并 simpleGetByIdMultipleCache 和 showTime 查询）。
     * 任一缓存缺失则抛异常。
     *
     * @param programId 节目 id
     * @return 含场次时间的节目详情
     */
    public ProgramVo simpleGetProgramAndShowMultipleCache(Long programId) {
        ProgramShowTime programShowTime =
                programShowTimeService.simpleSelectProgramShowTimeByProgramIdMultipleCache(programId);
        if (Objects.isNull(programShowTime)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST);
        }

        ProgramVo cachedVo = simpleGetByIdMultipleCache(programId);
        if (Objects.isNull(cachedVo)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }

        ProgramVo programVo = new ProgramVo();
        BeanUtil.copyProperties(cachedVo, programVo);
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());

        return programVo;
    }

    /**
     * 三级缓存兜底：本地 Caffeine → Redis → DB（double-checked locking）
     *
     * 内部 Redis 不存在时获取 ReentrantLock，二次检查后查 DB 回填，
     * 避免同一个节目在缓存过期瞬间被重复加载（cache stampede）。
     */
    public ProgramVo getById(Long programId, Long expireTime, TimeUnit timeUnit) {
        ProgramVo programVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId), ProgramVo.class);
        if (Objects.nonNull(programVo)) {
            return programVo;
        }  // Redis 命中直接返回
        log.info("查询节目详情 从Redis缓存没有查询到 节目id : {}", programId);
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_LOCK, new String[]{String.valueOf(programId)});
        if (!serviceLockTool.tryLock(lock, "GET_PROGRAM_LOCK:" + programId)) {
            // 等超时：先再查一次缓存（可能刚好被填好），仍没有就快速失败，不无锁重建
            programVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId),
                    ProgramVo.class);
            if (Objects.nonNull(programVo)) {
                return programVo;
            }
            throw new TicketFlowFrameException(BaseCode.CACHE_LOAD_LOCK_TIMEOUT);
        }
        try {
            return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId)
                    , ProgramVo.class,
                    () -> createProgramVo(programId)   //查 DB 回填
                    , expireTime,
                    timeUnit);
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 本地缓存兜底查询节目分组信息。
     * 缓存未命中时委托 getProgramGroup（走 Redis → DB 三级缓存）。
     *
     * @param programGroupId 节目分组 id
     * @return 节目分组 VO
     */
    public ProgramGroupVo getProgramGroupMultipleCache(Long programGroupId) {
        return localCacheProgramGroup.getCache(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId).getRelKey(),
                key -> getProgramGroup(programGroupId));
    }

    public ProgramGroupVo getProgramGroup(Long programGroupId) {
        ProgramGroupVo programGroupVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), ProgramGroupVo.class);
        if (Objects.nonNull(programGroupVo)) {
            return programGroupVo;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_GROUP_LOCK, new String[]{String.valueOf(programGroupId)});
        if (!serviceLockTool.tryLock(lock, "GET_PROGRAM_GROUP_LOCK:" + programGroupId)) {
            // 等超时：先再查一次缓存（可能刚好被填好），仍没有就快速失败，不无锁重建
            programGroupVo = redisCache.get(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId),
                    ProgramGroupVo.class);
            if (Objects.nonNull(programGroupVo)) {
                return programGroupVo;
            }
            throw new TicketFlowFrameException(BaseCode.CACHE_LOAD_LOCK_TIMEOUT);
        }
        try {
            programGroupVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId),
                    ProgramGroupVo.class);
            if (Objects.isNull(programGroupVo)) {
                programGroupVo = createProgramGroupVo(programGroupId);
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), programGroupVo,
                        CacheTtlUtil.calculateExpireSeconds(programGroupVo.getRecentShowTime()), TimeUnit.SECONDS);
            }
            return programGroupVo;
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 根据分类 ID 集合查询分类名称。
     * 用 MyBatis Plus 的 LambdaQueryWrapper 拼条件查询：
     * SELECT * FROM d_program_category WHERE id IN (id1, id2, ...)
     * Wrappers.lambdaQuery() 是 MyBatis Plus 提供的条件构造器，
     * ProgramCategory::getId 是 Java 8 方法引用，编译期就能检查字段名对不对。
     */
    public Map<Long, String> selectProgramCategoryMap(Collection<Long> programCategoryIdList) {
        LambdaQueryWrapper<ProgramCategory> pcLambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .in(ProgramCategory::getId, programCategoryIdList);
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(pcLambdaQueryWrapper);
        return programCategoryList
                .stream()
                .collect(Collectors.toMap(ProgramCategory::getId, ProgramCategory::getName, (v1, v2) -> v2));
    }

    /**
     * 查询多个节目的票档价格区间（最低价 ~ 最高价）。
     * 调用 TicketCategoryMapper 的自定义 SQL（不是内置方法），
     * SQL 在 XML 里：SELECT program_id, MIN(price), MAX(price) ... GROUP BY program_id
     * 返回结果每个节目一行（programId → {minPrice, maxPrice}）。
     */
    public Map<Long, TicketCategoryAggregate> selectTicketCategorieMap(List<Long> programIdList) {
        List<TicketCategoryAggregate> ticketCategorieList = ticketCategoryMapper.selectAggregateList(programIdList);
        return ticketCategorieList
                .stream()
                .collect(Collectors.toMap(TicketCategoryAggregate::getProgramId,
                        ticketCategory -> ticketCategory, (v1, v2) -> v2));
    }

    /**
     * V4 异步创建专用的座位锁定 + 余票扣减。
     *
     * @RepeatExecuteLimit 防止 Kafka 重复消费导致超卖。
     * 三步：1) 校验座位存在且为 NO_SOLD 2) 改状态 3) 扣余票（count 不匹配则回滚）。
     */
    /**
     * 座位锁定 + 票档库存扣减（V4 订单创建的核心方法）。
     * <p>
     * 三步走：
     * 1. 查座位（MP 条件查询：WHERE program_id=? AND id IN (?,?,?)）
     * 2. 校验每个座位都是"未售卖"状态
     * 3. 改座位状态为"锁定" + 扣减对应票档的 remainNumber
     *
     * @RepeatExecuteLimit durationTime=0 仅在执行期间防并发冲突，避免 60s 拦截导致网络重试被抛异常误弃
     * @Transactional 保证 seat.update + ticketCategory.reduceRemainNumber 在同一个事务里
     */
    @RepeatExecuteLimit(name = REDUCE_REMAIN_NUMBER, keys = {"#reduceRemainNumberDto.programId", "#reduceRemainNumberDto.seatIdList"}, durationTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateSeatLockAndTicketCategoryRemainNumber(ReduceRemainNumberDto reduceRemainNumberDto) {
        List<TicketCategoryCountDto> ticketCategoryCountDtoList = reduceRemainNumberDto.getTicketCategoryCountDtoList();
        List<Long> seatIdList = reduceRemainNumberDto.getSeatIdList();
        // 空座位列表直接抛业务异常，避免生成 "id IN ()" 触发 ShardingSphere SQL 解析异常
        if (CollectionUtil.isEmpty(seatIdList)) {
            throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
        }

        Long orderNumber = reduceRemainNumberDto.getOrderNumber();
        RedisKeyBuild lockRecordKey = (orderNumber != null)
                ? RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_ORDER_LOCK_RECORD, reduceRemainNumberDto.getProgramId(), orderNumber)
                : null;
        // 幂等核验：若同一订单号由于网络超时触发重试，且本单已成功锁定过 DB，直接返回 true，防止抛 40011 错误误弃订单
        if (lockRecordKey != null && Boolean.TRUE.equals(redisCache.hasKey(lockRecordKey))) {
            log.info("检测到订单锁定重试请求，凭据匹配，直接幂等返回成功 orderNumber: {}", orderNumber);
            return Boolean.TRUE;
        }

        // MyBatis Plus LambdaQueryWrapper：用面向对象方式拼 WHERE 条件，避免写死字符串字段名
        // 相当于：SELECT * FROM d_seat WHERE program_id = ? AND id IN (?, ?, ?)
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper =
                Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId, reduceRemainNumberDto.getProgramId())
                        .in(Seat::getId, seatIdList);

        // selectList 是 MyBatis Plus 内置的查询方法，返回符合条件的所有记录
        List<Seat> seatList = seatMapper.selectList(seatLambdaQueryWrapper);

        if (CollectionUtil.isEmpty(seatList)) {
            throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        if (seatList.size() != seatIdList.size()) {
            throw new TicketFlowFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
        }
        // 校验：所有座位必须都是"未售卖"（SellStatus.NO_SOLD）状态才能锁定
        for (Seat seat : seatList) {
            if (!Objects.equals(seat.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
                throw new TicketFlowFrameException(BaseCode.SEAT_IS_NOT_NOT_SOLD);
            }
        }

        // MyBatis Plus LambdaUpdateWrapper：面向对象拼 UPDATE 条件
        // 相当于：UPDATE d_seat SET sell_status = ? WHERE program_id = ? AND id IN (?, ?, ?)
        LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(Seat.class)
                        .eq(Seat::getProgramId, reduceRemainNumberDto.getProgramId())
                        .in(Seat::getId, seatIdList);
        Seat updateSeat = new Seat();
        updateSeat.setSellStatus(reduceRemainNumberDto.getSellStatus());
        seatMapper.update(updateSeat, seatLambdaUpdateWrapper);

        // 扣减每个票档的库存（调自定义 SQL，见 TicketCategoryMapper.xml）
        int updateRemainNumberCount = 0;
        for (TicketCategoryCountDto ticketCategoryCountDto : ticketCategoryCountDtoList) {
            // reduceRemainNumber 的 SQL 里有 WHERE remain_number >= #{amount}，库存不够返回 0
            updateRemainNumberCount = updateRemainNumberCount + ticketCategoryMapper.reduceRemainNumber(
                    ticketCategoryCountDto.getCount(), ticketCategoryCountDto.getTicketCategoryId(),
                    reduceRemainNumberDto.getProgramId());
        }
        // 如果有任何一个票档扣减失败（affected rows = 0），说明库存不够，抛异常回滚事务
        if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
            throw new TicketFlowFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
        }
        if (lockRecordKey != null) {
            redisCache.set(lockRecordKey, "1", 10, TimeUnit.MINUTES);
        }
        return true;
    }

    /**
     * V1-V3：DB 直接改 SOLD（乐观：预期座位无人抢），失败后靠 Lua 回滚
     * V4：  先校验 LOCK 状态（悲观：只有锁定中的座位才能支付/取消），
     * 再按 pay/cancel 分别走 SOLD / NO_SOLD + 库存归还
     */
    @RepeatExecuteLimit(name = PAY_OR_CANCEL_PROGRAM_ORDER, keys = {"#programOperateDataDto.programId", "#programOperateDataDto.orderNumber", "#programOperateDataDto.sellStatus", "#programOperateDataDto.seatIdList"}, durationTime = 10)
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateProgramData(ProgramOperateDataDto programOperateDataDto) {
        List<Long> seatIdList = programOperateDataDto.getSeatIdList();
        // 空座位列表直接抛业务异常，避免生成 "id IN ()" 触发 ShardingSphere SQL 解析异常
        if (CollectionUtil.isEmpty(seatIdList)) {
            throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper =
                Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                        .in(Seat::getId, seatIdList);
        List<Seat> seatList = seatMapper.selectList(seatLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(seatList)) {
            throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        if (seatList.size() != seatIdList.size()) {
            throw new TicketFlowFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
        }
        //座位的操作状态只能是售卖或者未售卖
        if (!Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.SOLD.getCode()) &&
                !Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
            throw new TicketFlowFrameException(BaseCode.SEAT_OPERATE_IS_NOT_NOT_SOLD_OR_SOLD);
        }
        Integer orderVersion = programOperateDataDto.getOrderVersion();
        // V1-V3：直接在 DB 检查 SOLD 并更新（无 LOCK 中间态）；orderVersion 为空按历史版本语义处理
        if (!ProgramOrderVersion.V4_VERSION.getValue().equals(orderVersion)) {
            // V1-V3：无 LOCK 中间态，按请求的 sellStatus 直接落库。
            // 支付（SOLD）：座位置 SOLD 并扣减 remain；取消（NO_SOLD）：座位置 NO_SOLD 并归还 remain。
            // 修正点：原先无视 sellStatus 一律按支付处理（置 SOLD + 扣库存），取消会被当成支付。
            boolean toSold = Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.SOLD.getCode());
            for (Seat seat : seatList) {
                if (toSold && Objects.equals(seat.getSellStatus(), SellStatus.SOLD.getCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_SOLD);
                }
            }
            LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(Seat.class)
                            .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                            .in(Seat::getId, seatIdList);
            Seat updateSeat = new Seat();
            updateSeat.setSellStatus(toSold ? SellStatus.SOLD.getCode() : SellStatus.NO_SOLD.getCode());
            seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
            // 逐票档扣减/归还库存：复用带 remain_number 下限保护的单条 SQL，
            // 替代原先 `<foreach>` 拼接多条 UPDATE（多票档时是非法 SQL）的批量方法。
            for (TicketCategoryCountDto ticketCategoryCountDto : programOperateDataDto.getTicketCategoryCountDtoList()) {
                int affected = toSold
                        ? ticketCategoryMapper.reduceRemainNumber(ticketCategoryCountDto.getCount(),
                                ticketCategoryCountDto.getTicketCategoryId(), programOperateDataDto.getProgramId())
                        : ticketCategoryMapper.increaseRemainNumber(ticketCategoryCountDto.getCount(),
                                ticketCategoryCountDto.getTicketCategoryId(), programOperateDataDto.getProgramId());
                if (affected <= 0) {
                    throw new TicketFlowFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
                }
            }
        } else {
            // V4：座位有 LOCK 中间态，必须从 LOCK→SOLD（支付）或 LOCK→NO_SOLD（取消）
            for (Seat seat : seatList) {
                if (!Objects.equals(seat.getSellStatus(), SellStatus.LOCK.getCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_IS_NOT_NOT_LOCK);
                }
            }

            Seat updateSeat = new Seat();
            List<TicketCategoryCountDto> ticketCategoryCountDtoList = programOperateDataDto.getTicketCategoryCountDtoList();
            //订单支付成功的操作
            if (Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.SOLD.getCode())) {
                updateSeat.setSellStatus(SellStatus.SOLD.getCode());
                LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(Seat.class)
                                .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                                .in(Seat::getId, seatIdList);
                seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
            } else if (Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
                //订单取消的操作：无论 NO_SOLD 还是 LOCK 都置回 NO_SOLD
                updateSeat.setSellStatus(SellStatus.NO_SOLD.getCode());
                LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(Seat.class)
                                .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                                .in(Seat::getId, seatIdList);
                seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
                int updateRemainNumberCount = 0;
                for (TicketCategoryCountDto ticketCategoryCountDto : ticketCategoryCountDtoList) {
                    updateRemainNumberCount = updateRemainNumberCount + ticketCategoryMapper.increaseRemainNumber(
                            ticketCategoryCountDto.getCount(), ticketCategoryCountDto.getTicketCategoryId(),
                            programOperateDataDto.getProgramId());
                }
                if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
                    throw new TicketFlowFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
                }
            }
        }
        return true;
    }

    /**
     * 查询座位当前售卖状态（只读，不做任何状态变更）。
     * 订单服务支付/取消后用它核对 DB 是否已经收敛到目标状态：
     * operateProgramData 的响应可能丢失，重试前先把"已生效"和"没生效"分开。
     * 查不到的座位不返回（调用方按缺失处理，视为未收敛）。
     */
    public List<SeatSellStatusVo> selectSeatSellStatus(SeatSellStatusDto seatSellStatusDto) {
        List<Long> seatIdList = seatSellStatusDto.getSeatIdList();
        // 空列表直接返回，避免 "id IN ()" 触发 SQL 解析异常
        if (CollectionUtil.isEmpty(seatIdList)) {
            return new ArrayList<>();
        }
        List<Seat> seatList = seatMapper.selectList(
                Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId, seatSellStatusDto.getProgramId())
                        .in(Seat::getId, seatIdList));
        if (CollectionUtil.isEmpty(seatList)) {
            return new ArrayList<>();
        }
        List<SeatSellStatusVo> seatSellStatusVoList = new ArrayList<>(seatList.size());
        for (Seat seat : seatList) {
            SeatSellStatusVo seatSellStatusVo = new SeatSellStatusVo();
            seatSellStatusVo.setSeatId(seat.getId());
            seatSellStatusVo.setSellStatus(seat.getSellStatus());
            seatSellStatusVoList.add(seatSellStatusVo);
        }
        return seatSellStatusVoList;
    }

    /**
     * 创建节目详情 VO。
     * 先查 DB（programMapper.selectById 是 MyBatis Plus 内置的根据主键查询），
     * 再通过 RPC 调用 baseDataClient 查地区名称，拼成最终返回对象。
     */
    private ProgramVo createProgramVo(Long programId) {
        ProgramVo programVo = new ProgramVo();
        // ⬇ MyBatis Plus 内置方法：SELECT * FROM d_program WHERE id = #{programId}
        Program program =
                Optional.ofNullable(programMapper.selectById(programId))
                        .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST));
        BeanUtil.copyProperties(program, programVo);
        AreaGetDto areaGetDto = new AreaGetDto();
        areaGetDto.setId(program.getAreaId());
        ApiResponse<AreaVo> areaResponse = baseDataClient.getById(areaGetDto);
        if (Objects.equals(areaResponse.getCode(), ApiResponse.ok().getCode())) {
            if (Objects.nonNull(areaResponse.getData())) {
                programVo.setAreaName(areaResponse.getData().getName());
            }
        } else {
            log.error("base-data rpc getById error areaResponse:{}", JSON.toJSONString(areaResponse));
        }
        return programVo;
    }

    /**
     * 从 DB 查询节目分组并组装 VO，包含场次列表（programJson 解析为 ProgramSimpleInfoVo）。
     *
     * @param programGroupId 节目分组 id
     * @return 节目分组 VO
     */
    private ProgramGroupVo createProgramGroupVo(Long programGroupId) {
        ProgramGroupVo programGroupVo = new ProgramGroupVo();
        ProgramGroup programGroup =
                Optional.ofNullable(programGroupMapper.selectById(programGroupId))
                        .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_GROUP_NOT_EXIST));
        programGroupVo.setId(programGroup.getId());
        programGroupVo.setProgramSimpleInfoVoList(JSON.parseArray(programGroup.getProgramJson(), ProgramSimpleInfoVo.class));
        programGroupVo.setRecentShowTime(programGroup.getRecentShowTime());
        return programGroupVo;
    }

    /**
     * 获取所有上架节目的 ID 列表。
     * 用于后台批量任务（如预热、数据同步）遍历节目。
     *
     * @return 所有上架节目的 id 列表
     */
    public List<Long> getAllProgramIdList() {
        LambdaQueryWrapper<Program> programLambdaQueryWrapper =
                Wrappers.lambdaQuery(Program.class).eq(Program::getProgramStatus, BusinessStatus.YES.getCode())
                        .select(Program::getId);
        List<Program> programs = programMapper.selectList(programLambdaQueryWrapper);
        return programs.stream().map(Program::getId).collect(Collectors.toList());
    }

    /**
     * 直接从数据库查询节目完整详情（含分类名称、场次时间）。
     * 不走任何缓存，适合后台管理或缓存重建时使用。
     *
     * @param programId 节目 id
     * @return 节目完整详情
     */
    public ProgramVo getDetailFromDb(Long programId) {
        //从数据库查询节目数据
        ProgramVo programVo = createProgramVo(programId);

        //设置节目类型相关信息
        ProgramCategory programCategory = getProgramCategory(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategory(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }

        //查询节目演出时间
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                Wrappers.lambdaQuery(ProgramShowTime.class).eq(ProgramShowTime::getProgramId, programId);
        ProgramShowTime programShowTime = Optional.ofNullable(programShowTimeMapper.selectOne(programShowTimeLambdaQueryWrapper))
                .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST));

        //组装演出时间信息
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());

        return programVo;
    }

    /**
     * 异步预热当前登录用户的购票人列表到 Redis。
     * 仅对高热节目、已登录用户生效，减少下单时的 RPC 调用耗时。
     * 缓存 key 不存在时由业务线程池异步写入，TTL 由 Redis 默认策略管理。
     */
    private void preloadTicketUserList(Integer highHeat) {
        if (Objects.equals(highHeat, BusinessStatus.NO.getCode())) {
            return;
        }
        String userId = BaseParameterHolder.getParameter(USER_ID);
        String code = BaseParameterHolder.getParameter(CODE);
        if (StringUtil.isEmpty(userId) || StringUtil.isEmpty(code)) {
            return;
        }
        Boolean userLogin =
                redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, userId));
        if (!userLogin) {
            return;
        }
        BusinessThreadPool.execute(() -> {
            try {
                if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST, userId))) {
                    TicketUserListDto ticketUserListDto = new TicketUserListDto();
                    ticketUserListDto.setUserId(Long.parseLong(userId));
                    ApiResponse<List<TicketUserVo>> apiResponse = userClient.list(ticketUserListDto);
                    if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        Optional.ofNullable(apiResponse.getData()).filter(CollectionUtil::isNotEmpty)
                                .ifPresent(ticketUserVoList -> redisCache.set(RedisKeyBuild.createRedisKey(
                                        RedisKeyManage.TICKET_USER_LIST, userId), ticketUserVoList));
                    } else {
                        log.warn("userClient.select 调用失败 apiResponse : {}", JSON.toJSONString(apiResponse));
                    }
                }

            } catch (Exception e) {
                log.error("预热加载购票人列表失败", e);
            }
        });
    }

    /**
     * 异步预热当前登录用户对该节目的下单数量到 Redis。
     * 用于下单时的限购校验（防止超限），减少同步 RPC 调用。
     * TTL = tokenExpireTime + 1 分钟，与登录态过期时间对齐。
     */
    private void preloadAccountOrderCount(Long programId) {
        String userId = BaseParameterHolder.getParameter(USER_ID);
        String code = BaseParameterHolder.getParameter(CODE);
        if (StringUtil.isEmpty(userId) || StringUtil.isEmpty(code)) {
            return;
        }
        Boolean userLogin =
                redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, userId));
        if (!userLogin) {
            return;
        }
        BusinessThreadPool.execute(() -> {
            try {
                if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, programId))) {
                    AccountOrderCountDto accountOrderCountDto = new AccountOrderCountDto();
                    accountOrderCountDto.setUserId(Long.parseLong(userId));
                    accountOrderCountDto.setProgramId(programId);
                    ApiResponse<AccountOrderCountVo> apiResponse = orderClient.accountOrderCount(accountOrderCountDto);
                    if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        Optional.ofNullable(apiResponse.getData())
                                .ifPresent(accountOrderCountVo -> redisCache.setIfAbsent(
                                        RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, programId),
                                        accountOrderCountVo.getCount(), tokenExpireManager.getTokenExpireTime() + 1,
                                        TimeUnit.MINUTES));
                    } else {
                        log.warn("orderClient.accountOrderCount 调用失败 apiResponse : {}", JSON.toJSONString(apiResponse));
                    }
                }
            } catch (Exception e) {
                log.error("预热加载账户订单数量失败", e);
            }
        });
    }

    /**
     * 本地缓存兜底查询节目分类。
     *
     * @param programCategoryId 分类 id
     * @return 节目分类实体
     */
    public ProgramCategory getProgramCategoryMultipleCache(Long programCategoryId) {
        return localCacheProgramCategory.get(String.valueOf(programCategoryId),
                key -> getProgramCategory(programCategoryId));
    }

    /**
     * 委托 ProgramCategoryService 查询分类。
     *
     * @param programCategoryId 分类 id
     * @return 节目分类实体
     */
    public ProgramCategory getProgramCategory(Long programCategoryId) {
        return programCategoryService.getProgramCategory(programCategoryId);
    }

    /**
     * 后台重置节目数据：将所有座位还原为未售卖、恢复票档库存、清理所有缓存。
     * 用于数据异常修复或活动结束后的重置操作。
     *
     * @param programResetExecuteDto 重置入参（节目 id）
     * @return 执行是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean resetExecute(ProgramResetExecuteDto programResetExecuteDto) {
        Long programId = programResetExecuteDto.getProgramId();
        //查出该节目下锁定和已售卖的座位
        LambdaQueryWrapper<Seat> seatQueryWrapper =
                Wrappers.lambdaQuery(Seat.class).eq(Seat::getProgramId, programId)
                        .in(Seat::getSellStatus, SellStatus.LOCK.getCode(), SellStatus.SOLD.getCode());
        List<Seat> seatList = seatMapper.selectList(seatQueryWrapper);
        if (CollectionUtil.isNotEmpty(seatList)) {
            //执行到这里说明有锁定和已售卖的座位，那么就把该节目下的座位都重置一遍
            LambdaUpdateWrapper<Seat> seatUpdateWrapper =
                    Wrappers.lambdaUpdate(Seat.class).eq(Seat::getProgramId, programId);
            Seat seatUpdate = new Seat();
            seatUpdate.setSellStatus(SellStatus.NO_SOLD.getCode());
            seatMapper.update(seatUpdate, seatUpdateWrapper);
        }
        //查询该节目下的票档
        LambdaQueryWrapper<TicketCategory> ticketCategoryQueryWrapper =
                Wrappers.lambdaQuery(TicketCategory.class).eq(TicketCategory::getProgramId, programId);
        List<TicketCategory> ticketCategories = ticketCategoryMapper.selectList(ticketCategoryQueryWrapper);
        if (CollectionUtil.isNotEmpty(ticketCategories)) {
            for (TicketCategory ticketCategory : ticketCategories) {
                Long remainNumber = ticketCategory.getRemainNumber();
                Long totalNumber = ticketCategory.getTotalNumber();
                //如果总数和剩余数不一致，则进行重置
                if (!(remainNumber.equals(totalNumber))) {
                    TicketCategory ticketCategoryUpdate = new TicketCategory();
                    ticketCategoryUpdate.setRemainNumber(totalNumber);

                    LambdaUpdateWrapper<TicketCategory> ticketCategoryUpdateWrapper =
                            Wrappers.lambdaUpdate(TicketCategory.class)
                                    .eq(TicketCategory::getProgramId, programId)
                                    .eq(TicketCategory::getId, ticketCategory.getId());
                    ticketCategoryMapper.update(ticketCategoryUpdate, ticketCategoryUpdateWrapper);
                }
            }
        }
        //统一清理集中缓存、本实例本地缓存并广播通知集群各实例
        evictProgramCache(programId);
        return true;
    }

    /**
     * 统一节目缓存失效：清理 Redis 集中缓存、当前实例本地缓存，并通过 Redis Stream 广播通知集群所有实例。
     *
     * @param programId 节目 id
     */
    public void evictProgramCache(Long programId) {
        Program program = programMapper.selectById(programId);
        delRedisData(programId);
        Long programGroupId = Objects.nonNull(program) ? program.getProgramGroupId() : null;
        delLocalCache(programId, programGroupId);
        String payload = Objects.nonNull(programGroupId) ? (programId + ":" + programGroupId) : String.valueOf(programId);
        redisStreamPushHandler.push(payload);
    }

    /**
     * 删除指定节目的所有 Redis 缓存数据。
     * 涉及节目详情、分组、场次、座位（三态 hash）、票档列表及余票、订单记录、丢弃订单。
     * 采用精确 Key 批量删除，坚决杜绝 KEYS 模糊扫描，消除 Redis 阻塞与 Cluster 跨 Slot 隐患。
     *
     * @param programId 节目 id
     */
    public void delRedisData(Long programId) {
        Program program = Optional.ofNullable(programMapper.selectById(programId))
                .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST));
        List<RedisKeyBuild> keys = new ArrayList<>(16);
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId));
        if (Objects.nonNull(program.getProgramGroupId())) {
            keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, program.getProgramGroupId()));
        }
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, programId));

        // 精确查出该节目下的票档列表，精准构建座位与余票 key，完全杜绝 KEYS 模糊扫描
        LambdaQueryWrapper<TicketCategory> tcWrapper = Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programId)
                .select(TicketCategory::getId);
        List<TicketCategory> ticketCategories = ticketCategoryMapper.selectList(tcWrapper);
        if (CollectionUtil.isNotEmpty(ticketCategories)) {
            for (TicketCategory tc : ticketCategories) {
                Long tcId = tc.getId();
                keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, tcId));
                keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, tcId));
                keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, tcId));
                keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, tcId));
            }
        }
        redisCache.del(keys);
    }

    /**
     * 将节目置为无效（下架）：更新 DB 状态、清理缓存、推送 Redis Stream 事件、删除 ES 索引。
     *
     * @param programInvalidDto 节目失效入参
     * @return 是否成功
     */
    public Boolean invalid(final ProgramInvalidDto programInvalidDto) {
        Program program = new Program();
        program.setId(programInvalidDto.getId());
        program.setProgramStatus(BusinessStatus.NO.getCode());  // ① 更新 DB：状态改为下架
        int result = programMapper.updateById(program);
        if (result > 0) {
            evictProgramCache(programInvalidDto.getId());  // ② 统一清理缓存并广播失效
            programEs.deleteByProgramId(programInvalidDto.getId());  // ③ 删 ES 索引
            return true;
        } else {
            return false;
        }
    }

    /**
     * 删除指定节目的所有本地缓存（节目、分组、场次、票档）。
     *
     * @param programId 节目 id
     */
    public void delLocalCache(Long programId) {
        delLocalCache(programId, null);
    }

    /**
     * 删除指定节目的所有本地缓存（节目、分组、场次、票档）。
     * 纯内存操作，坚决不查 MySQL，避免全实例广播时击穿数据库。
     *
     * @param programId      节目 id
     * @param programGroupId 节目分组 id（可为 null）
     */
    public void delLocalCache(Long programId, Long programGroupId) {
        log.info("删除本地缓存 programId : {}, programGroupId : {}", programId, programGroupId);
        String programRelKey = RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey();
        if (Objects.nonNull(programGroupId)) {
            localCacheProgramGroup.del(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId).getRelKey());
        } else {
            ProgramVo cachedProgram = localCacheProgram.getCache(programRelKey);
            if (Objects.nonNull(cachedProgram) && Objects.nonNull(cachedProgram.getProgramGroupId())) {
                localCacheProgramGroup.del(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, cachedProgram.getProgramGroupId()).getRelKey());
            }
        }
        localCacheProgram.del(programRelKey);
        localCacheProgramShowTime.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey());
        localCacheTicketCategory.del(programId);
    }

    /**
     * 节目数据预热：先重置 DB 数据（resetExecute），再加载完整详情到缓存（getDetailV2），
     * 最后预热座位分辩率和票档余票到 Redis。
     * 通常在节目上架前或缓存全量失效后调用。
     *
     * @param programDataPreheatDto 预热入参（节目 id）
     * @return 是否成功
     */
    public Boolean dataPreheat(ProgramDataPreheatDto programDataPreheatDto) {
        ProgramResetExecuteDto programResetExecuteDto = new ProgramResetExecuteDto();
        programResetExecuteDto.setProgramId(programDataPreheatDto.getProgramId());
        //先把数据库中的座位和库存数据重置，然后删除本地缓存和redis缓存
        programService.resetExecute(programResetExecuteDto);

        ProgramGetDto programGetDto = new ProgramGetDto();
        programGetDto.setId(programDataPreheatDto.getProgramId());
        //再将节目相关的数据预热到缓存中，包括本地缓存和redis缓存
        ProgramVo programVo = getDetailV2(programGetDto);
        if (Objects.isNull(programVo)) {
            return false;
        }
        //再将座位和库存数据预热到redis缓存中
        Date showDayTime = programVo.getShowDayTime();
        List<TicketCategoryVo> ticketCategoryVoList = programVo.getTicketCategoryVoList();
        for (TicketCategoryVo ticketCategoryVo : ticketCategoryVoList) {
            seatService.selectSeatResolution(programDataPreheatDto.getProgramId(),
                    ticketCategoryVo.getId(), CacheTtlUtil.calculateExpireSeconds(showDayTime), TimeUnit.SECONDS);
            ticketCategoryService.getRedisRemainNumberResolution(programDataPreheatDto.getProgramId(), ticketCategoryVo.getId());
        }
        return true;
    }
}

