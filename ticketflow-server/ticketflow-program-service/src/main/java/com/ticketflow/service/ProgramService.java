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
import com.ticketflow.servicelock.annotion.ServiceLock;
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
import static com.ticketflow.core.DistributedLockConstants.GET_PROGRAM_LOCK;
import static com.ticketflow.core.DistributedLockConstants.PROGRAM_GROUP_LOCK;
import static com.ticketflow.core.DistributedLockConstants.PROGRAM_LOCK;
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
        //使用elasticsearch查询
        PageVo<ProgramListVo> pageVo = programEs.search(programSearchDto);
        if (pageVo != null && CollectionUtil.isNotEmpty(pageVo.getList())) {
            return pageVo;
        }
        //ES 无数据或不可用时走 DB 兜底（DB 侧无搜索词条件，仅按分类/时间过滤）
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

        List<ProgramHomeVo> programHomeVoList = programEs.selectHomeList(programListDto);
        if (CollectionUtil.isNotEmpty(programHomeVoList)) {
            return programHomeVoList;
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
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(), programGetDto);
        return getDetailV2(programGetDto);
    }

    /**
     * 查询节目详情V1
     *
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public ProgramVo detailV1(ProgramGetDto programGetDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(), programGetDto);
        return getDetail(programGetDto);
    }

    /**
     * 查询节目详情V2
     *
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public ProgramVo detailV2(ProgramGetDto programGetDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(), programGetDto);
        return getDetailV2(programGetDto);
    }

    /**
     * 查询节目详情执行
     *
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     *
     */
    public ProgramVo getDetail(ProgramGetDto programGetDto) {
        // 1. 查询场次时间 + 节目详情（三级缓存兜底）
        ProgramShowTime programShowTime = programShowTimeService.selectProgramShowTimeByProgramId(programGetDto.getId());
        ProgramVo programVo = programService.getById(programGetDto.getId(), DateUtils.countBetweenSecond(DateUtils.now(),
                programShowTime.getShowTime()), TimeUnit.SECONDS);
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());

        // 2. 查询节目分组信息
        ProgramGroupVo programGroupVo = programService.getProgramGroup(programVo.getProgramGroupId());
        programVo.setProgramGroupVo(programGroupVo);

        // 3. 预热购票人列表和下单数量（高热节目 + 已登录用户）
        preloadTicketUserList(programVo.getHighHeat());

        preloadAccountOrderCount(programVo.getId());

        // 4. 查询分类名称
        ProgramCategory programCategory = getProgramCategory(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategory(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }

        // 5. 查询票档列表
        List<TicketCategoryVo> ticketCategoryVoList =
                ticketCategoryService.selectTicketCategoryListByProgramId(programVo.getId(),
                        DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);
        programVo.setTicketCategoryVoList(ticketCategoryVoList);

        return programVo;
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

        ProgramVo programVo = programService.getByIdMultipleCache(programGetDto.getId(), programShowTime.getShowTime());

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

        // 5. 多级缓存查询票档列表
        List<TicketCategoryVo> ticketCategoryVoList = ticketCategoryService
                .selectTicketCategoryListByProgramIdMultipleCache(programVo.getId(), programShowTime.getShowTime());
        programVo.setTicketCategoryVoList(ticketCategoryVoList);

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
                key -> {
                    log.info("查询节目详情 从本地缓存没有查询到 节目id : {}", programId);
                    ProgramVo programVo = getById(programId, DateUtils.countBetweenSecond(DateUtils.now(), showTime),
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

        ProgramVo programVo = simpleGetByIdMultipleCache(programId);
        if (Objects.isNull(programVo)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }

        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());

        return programVo;
    }

    /**
     * 三级缓存兜底：本地 Caffeine → Redis → DB（double-checked locking）
     *
     * @ServiceLock(Read) 允许并发读，但防止 N 个线程同时进入该方法；
     * 内部 Redis 不存在时获取 ReentrantLock，二次检查后查 DB 回填，
     * 避免同一个节目在缓存过期瞬间被重复加载（cache stampede）。
     */
    @ServiceLock(lockType = LockType.Read, name = PROGRAM_LOCK, keys = {"#programId"})
    public ProgramVo getById(Long programId, Long expireTime, TimeUnit timeUnit) {
        ProgramVo programVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId), ProgramVo.class);
        if (Objects.nonNull(programVo)) {
            return programVo;
        }
        log.info("查询节目详情 从Redis缓存没有查询到 节目id : {}", programId);
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_LOCK, new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId)
                    , ProgramVo.class,
                    () -> createProgramVo(programId)
                    , expireTime,
                    timeUnit);
        } finally {
            lock.unlock();
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

    @ServiceLock(lockType = LockType.Read, name = PROGRAM_GROUP_LOCK, keys = {"#programGroupId"})
    public ProgramGroupVo getProgramGroup(Long programGroupId) {
        ProgramGroupVo programGroupVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), ProgramGroupVo.class);
        if (Objects.nonNull(programGroupVo)) {
            return programGroupVo;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_LOCK, new String[]{String.valueOf(programGroupId)});
        lock.lock();
        try {
            programGroupVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId),
                    ProgramGroupVo.class);
            if (Objects.isNull(programGroupVo)) {
                programGroupVo = createProgramGroupVo(programGroupId);
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), programGroupVo,
                        DateUtils.countBetweenSecond(DateUtils.now(), programGroupVo.getRecentShowTime()), TimeUnit.SECONDS);
            }
            return programGroupVo;
        } finally {
            lock.unlock();
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
     * @RepeatExecuteLimit 防止 Kafka 重复消费导致超卖
     * @Transactional 保证 seat.update + ticketCategory.reduceRemainNumber 在同一个事务里
     */
    @RepeatExecuteLimit(name = REDUCE_REMAIN_NUMBER, keys = {"#reduceRemainNumberDto.programId", "#reduceRemainNumberDto.seatIdList"}, durationTime = 60)
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateSeatLockAndTicketCategoryRemainNumber(ReduceRemainNumberDto reduceRemainNumberDto) {
        List<TicketCategoryCountDto> ticketCategoryCountDtoList = reduceRemainNumberDto.getTicketCategoryCountDtoList();
        List<Long> seatIdList = reduceRemainNumberDto.getSeatIdList();

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
        return true;
    }

    /**
     * V1-V3：DB 直接改 SOLD（乐观：预期座位无人抢），失败后靠 Lua 回滚
     * V4：  先校验 LOCK 状态（悲观：只有锁定中的座位才能支付/取消），
     * 再按 pay/cancel 分别走 SOLD / NO_SOLD + 库存归还
     */
    @RepeatExecuteLimit(name = PAY_OR_CANCEL_PROGRAM_ORDER, keys = {"#programOperateDataDto.programId", "#programOperateDataDto.seatIdList"}, durationTime = 60)
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateProgramData(ProgramOperateDataDto programOperateDataDto) {
        List<Long> seatIdList = programOperateDataDto.getSeatIdList();
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
            for (Seat seat : seatList) {
                if (Objects.equals(seat.getSellStatus(), SellStatus.SOLD.getCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_SOLD);
                }
            }
            LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(Seat.class)
                            .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                            .in(Seat::getId, seatIdList);
            Seat updateSeat = new Seat();
            updateSeat.setSellStatus(SellStatus.SOLD.getCode());
            seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
            List<TicketCategoryCountDto> ticketCategoryCountDtoList = programOperateDataDto.getTicketCategoryCountDtoList();
            int updateRemainNumberCount =
                    ticketCategoryMapper.batchUpdateRemainNumber(ticketCategoryCountDtoList, programOperateDataDto.getProgramId());
            if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
                throw new TicketFlowFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
            }
        } else {
            // V4：座位有 LOCK 中间态，必须从 LOCK→SOLD（支付）或 LOCK→NO_SOLD（取消）
            for (Seat seat : seatList) {
                if (!Objects.equals(seat.getSellStatus(), SellStatus.LOCK.getCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_IS_NOT_NOT_LOCK);
                }
            }

            Seat updateSeat = new Seat();
            //订单支付成功的操作
            if (Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.SOLD.getCode())) {
                updateSeat.setSellStatus(SellStatus.SOLD.getCode());
                LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(Seat.class)
                                .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                                .in(Seat::getId, seatIdList);
                seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
            } else if (Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
                //订单取消的操作  
                updateSeat.setSellStatus(SellStatus.NO_SOLD.getCode());
                LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(Seat.class)
                                .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                                .in(Seat::getId, seatIdList);
                seatMapper.update(updateSeat, seatLambdaUpdateWrapper);
                List<TicketCategoryCountDto> ticketCategoryCountDtoList = programOperateDataDto.getTicketCategoryCountDtoList();
                int updateRemainNumberCount = 0;
                //把库存增加回去
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
                                .ifPresent(accountOrderCountVo -> redisCache.set(
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
        //删除缓存相关数据
        delRedisData(programId);
        //删除本地缓存数据
        delLocalCache(programId);
        return true;
    }

    /**
     * 删除指定节目的所有 Redis 缓存数据。
     * 涉及节目详情、分组、场次、座位（三态 hash）、票档列表及余票、订单记录、丢弃订单。
     * 使用 Lua 脚本批量删除以保证原子性。
     *
     * @param programId 节目 id
     */
    public void delRedisData(Long programId) {
        Program program = Optional.ofNullable(programMapper.selectById(programId))
                .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST));
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, program.getProgramGroupId()).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, "*").getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, "*").getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, "*").getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, "*").getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, programId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT_ALL).getRelKey());
        programDelCacheData.del(keys, new String[]{});
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
        program.setProgramStatus(BusinessStatus.NO.getCode());
        int result = programMapper.updateById(program);
        if (result > 0) {
            delRedisData(programInvalidDto.getId());
            redisStreamPushHandler.push(String.valueOf(programInvalidDto.getId()));
            programEs.deleteByProgramId(programInvalidDto.getId());
            return true;
        } else {
            return false;
        }
    }

    /**
     * 仅从本地缓存查询节目详情，不走 Redis 和 DB。
     * 用于并发场景下获取当前线程可见的快照数据。
     *
     * @param programGetDto 节目查询入参
     * @return 节目详情，本地缓存不存在时返回 null
     */
    public ProgramVo localDetail(final ProgramGetDto programGetDto) {
        return localCacheProgram.getCache(String.valueOf(programGetDto.getId()));
    }

    /**
     * 删除指定节目的所有本地缓存（节目、分组、场次、票档）。
     * 通常在后台重置或节目下架时调用，保证下次读取命中 Redis 后重新回填。
     *
     * @param programId 节目 id
     */
    public void delLocalCache(Long programId) {
        log.info("删除本地缓存 programId : {}", programId);
        localCacheProgram.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey());
        localCacheProgramGroup.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programId).getRelKey());
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
                    ticketCategoryVo.getId(), DateUtils.countBetweenSecond(DateUtils.now(), showDayTime), TimeUnit.SECONDS);
            ticketCategoryService.getRedisRemainNumberResolution(programDataPreheatDto.getProgramId(), ticketCategoryVo.getId());
        }
        return true;
    }
}

