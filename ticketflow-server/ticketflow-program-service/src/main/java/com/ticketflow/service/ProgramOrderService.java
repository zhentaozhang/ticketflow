package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.client.OrderClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.domain.PurchaseSeat;
import com.ticketflow.dto.DelayOrderCancelDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.entity.ProgramRecordTask;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.RecordType;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ProgramRecordTaskMapper;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.delaysend.DelayOrderCancelSend;
import com.ticketflow.service.domain.CreateOrderTemporaryData;
import com.ticketflow.service.kafka.CreateOrderMqDomain;
import com.ticketflow.service.kafka.CreateOrderSend;
import com.ticketflow.service.lua.ProgramCacheCreateOrderData;
import com.ticketflow.service.lua.ProgramCacheCreateOrderResolutionOperate;
import com.ticketflow.service.lua.ProgramCacheResolutionOperate;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.SeatVo;
import com.ticketflow.vo.TicketCategoryVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.GLIDE_LINE;

/**
 * 订单创建核心 Facade。
 * 聚合订单创建全流程：参数校验 → 防重复检查 → 余票锁定(Lua) → 座位锁定(Lua) →
 * 订单持久化(DB) → 支付回调 → 座位最终状态更新(Lua)
 * <p>
 * 对外暴露 3 条路径：
 * create()  — V1/V2 同步路径
 * createNew() — V3 同步路径（BaseProgramOrder 托管）
 * createNewAsync() — V4 Kafka 异步路径
 */
@Slf4j
@Service
public class ProgramOrderService {

    /**
     * 不选座自动匹配：候选座位被并发订单抢占时的最大重试次数（超出后抛抢占错误码）
     */
    private static final int AUTO_MATCH_RETRY_TIMES = 1;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private ProgramCacheResolutionOperate programCacheResolutionOperate;

    @Autowired
    ProgramCacheCreateOrderResolutionOperate programCacheCreateOrderResolutionOperate;

    @Autowired
    private DelayOrderCancelSend delayOrderCancelSend;

    @Autowired
    private CreateOrderSend createOrderSend;

    @Autowired
    private ProgramService programService;

    @Autowired
    private ProgramShowTimeService programShowTimeService;

    @Autowired
    private TicketCategoryService ticketCategoryService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private ProgramRecordTaskMapper programRecordTaskMapper;

    @Autowired
    private com.ticketflow.redis.RedisCache redisCache;

    /**
     * 自动匹配相邻座位（与 Lua find_adjacent_seats 算法一致）：
     * 按排号/列号排序后，滑动窗口寻找连续相邻的 seatCount 个座位。
     * 将匹配算法从 Redis 主线程移到应用层，避免全量 hvals + 排序阻塞 Redis。
     *
     * @param seatVoList 未售座位集合（no_sold hash 全量）
     * @param seatCount  需要匹配的座位数量
     * @return 匹配到的相邻座位；不足时返回空列表
     */
    public List<SeatVo> matchAdjacentSeats(List<SeatVo> seatVoList, int seatCount){
        if (CollectionUtil.isEmpty(seatVoList) || seatVoList.size() < seatCount) {
            return new ArrayList<>();
        }
        List<SeatVo> sortedSeatList = seatVoList.stream()
                .sorted(Comparator.comparing(SeatVo::getRowCode).thenComparing(SeatVo::getColCode))
                .toList();
        for (int i = 0; i <= sortedSeatList.size() - seatCount; i++) {
            boolean adjacent = true;
            for (int j = 0; j < seatCount - 1; j++) {
                SeatVo current = sortedSeatList.get(i + j);
                SeatVo next = sortedSeatList.get(i + j + 1);
                if (!(Objects.equals(current.getRowCode(), next.getRowCode())
                        && next.getColCode() - current.getColCode() == 1)) {
                    adjacent = false;
                    break;
                }
            }
            if (adjacent) {
                return new ArrayList<>(sortedSeatList.subList(i, i + seatCount));
            }
        }
        return new ArrayList<>();
    }

    /**
     * 获取购票时票档列表。
     * 传入选座列表则逐座校验票档存在性，否则按 ticketCategoryId 校验。
     *
     * @param programOrderCreateDto 订单创建参数
     * @param showTime              演出时间
     * @return 有效的票档 Vo 列表
     */
    public List<TicketCategoryVo> getTicketCategoryList(ProgramOrderCreateDto programOrderCreateDto, Date showTime) {
        List<TicketCategoryVo> getTicketCategoryVoList = new ArrayList<>();
        List<TicketCategoryVo> ticketCategoryVoList =
                ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(programOrderCreateDto.getProgramId(),
                        showTime);
        Map<Long, TicketCategoryVo> ticketCategoryVoMap =
                ticketCategoryVoList.stream()
                        .collect(Collectors.toMap(TicketCategoryVo::getId, ticketCategoryVo -> ticketCategoryVo));
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            for (SeatDto seatDto : seatDtoList) {
                TicketCategoryVo ticketCategoryVo = ticketCategoryVoMap.get(seatDto.getTicketCategoryId());
                if (Objects.nonNull(ticketCategoryVo)) {
                    getTicketCategoryVoList.add(ticketCategoryVo);
                } else {
                    throw new TicketFlowFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2);
                }
            }
        } else {
            TicketCategoryVo ticketCategoryVo = ticketCategoryVoMap.get(programOrderCreateDto.getTicketCategoryId());
            if (Objects.nonNull(ticketCategoryVo)) {
                getTicketCategoryVoList.add(ticketCategoryVo);
            } else {
                throw new TicketFlowFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2);
            }
        }
        return getTicketCategoryVoList;
    }

    /**
     * V1/V2 同步入口（兼容保留）。
     * 委托 {@link #createNew} 执行：Lua 原子校验余票与座位 → RPC 调 order-service 建单。
     * <p>
     * 历史上 V1/V2 走"Java 非原子校验 + 无校验 Lua 扣减"，跨版本并发存在超卖窗口，
     * 现统一收敛到带校验的 Lua（programDataCreateOrderResolution）保证并发安全。
     */
    public String create(ProgramOrderCreateDto programOrderCreateDto, Integer orderVersion) {
        return createNew(programOrderCreateDto, orderVersion);
    }


    /**
     * V3 同步创建路径。
     * Lua 原子扣减 Redis 余票与锁定座位，然后 RPC 调 order-service 创建订单。
     *
     * @param programOrderCreateDto 订单创建参数
     * @param orderVersion          订单版本号
     * @return 订单编号
     */
    public String createNew(ProgramOrderCreateDto programOrderCreateDto, Integer orderVersion) {
        CreateOrderTemporaryData createOrderTemporaryData = createOrderOperateProgramCacheResolution(programOrderCreateDto);
        List<SeatVo> purchaseSeatList = createOrderTemporaryData.getPurchaseSeatList().stream().map(purchaseSeat -> {
            SeatVo seatVo = new SeatVo();
            BeanUtils.copyProperties(purchaseSeat, seatVo);
            return seatVo;
        }).collect(Collectors.toList());
        return doCreate(programOrderCreateDto, purchaseSeatList, orderVersion);
    }

    /**
     * V4 全异步创建路径。
     * Lua 扣减缓存后立即发送 Kafka 消息，由 consumer 异步建单，调用方无需等待。
     *
     * @param programOrderCreateDto 订单创建参数
     * @param orderVersion          订单版本号
     * @return 订单编号（Kafka 中预生成）
     */
    public String createNewAsync(ProgramOrderCreateDto programOrderCreateDto, Integer orderVersion) {
        //操作redis
        CreateOrderTemporaryData createOrderTemporaryData = createOrderOperateProgramCacheResolution(programOrderCreateDto);
        //发送kafka
        return doCreateV2(programOrderCreateDto, createOrderTemporaryData, orderVersion);
    }

    /**
     * V4 异步路径的锁外发送段（配合 Strategy 在锁内先做 Lua 扣减、锁外再发送）。
     * Lua 扣减已在调用方（锁内）完成，这里只负责构建参数 + Kafka 发送建单消息 + 投递延迟取消队列。
     * 将 Kafka 同步等待发送确认移出锁，可大幅缩短锁持有时间、降低锁竞争失败率。
     *
     * @param programOrderCreateDto      订单创建参数
     * @param createOrderTemporaryData   锁内 Lua 扣减的临时数据（座位/记录标识）
     * @param orderVersion               订单版本号
     * @return 订单编号（Kafka 中预生成）
     */
    public String createNewAsyncAfterLock(ProgramOrderCreateDto programOrderCreateDto,
                                          CreateOrderTemporaryData createOrderTemporaryData,
                                          Integer orderVersion) {
        return doCreateV2(programOrderCreateDto, createOrderTemporaryData, orderVersion);
    }

    /**
     * 执行 Lua 脚本完成 Redis 缓存原子操作。
     * 预热票档/座位缓存 → 构造 Lua 参数 → 原子扣减余票 + 锁定座位 + 写入操作记录。
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 包含操作标识与已锁定座位列表的临时数据
     */
    public CreateOrderTemporaryData createOrderOperateProgramCacheResolution(ProgramOrderCreateDto programOrderCreateDto) {
        //从多级缓存中查找节目演出时间ProgramShowTime
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programOrderCreateDto.getProgramId());
        //查询对应的票档类型
        List<TicketCategoryVo> getTicketCategoryList =
                getTicketCategoryList(programOrderCreateDto, programShowTime.getShowTime());
        //遍历得到的票档
        for (TicketCategoryVo ticketCategory : getTicketCategoryList) {
            Long ticketCategoryId = ticketCategory.getId();
            //座位缓存已预热时跳过全量拉取：该 hash 每档 2 万 field，全量读+JSON 反序列化开销大，
            //在锁内执行会拉长锁持有时间、放大锁竞争失败（70005）。仅缓存缺失时预热。
            if (!hasSeatResolutionCache(programOrderCreateDto.getProgramId(), ticketCategoryId)) {
                seatService.selectSeatResolution(programOrderCreateDto.getProgramId(), ticketCategoryId,
                        DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);
            }
            //余票缓存已预热时跳过：getRedisRemainNumberResolution 带 @ServiceLock(Read) 分布式读锁，
            //锁内每次调用会获取 Redisson 读锁拉长锁持有时间；返回值此处未使用，仅需确保缓存存在。
            if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programOrderCreateDto.getProgramId(), ticketCategoryId))) {
                ticketCategoryService.getRedisRemainNumberResolution(
                        programOrderCreateDto.getProgramId(), ticketCategoryId);
            }
        }
        Long programId = programOrderCreateDto.getProgramId();
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<String> keys = new ArrayList<>();
        String[] data = new String[3];
        //更新票档数据集合
        JSONArray jsonArray = new JSONArray();
        //添加座位数据集合
        JSONArray addSeatDatajsonArray = new JSONArray();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            keys.add("1");
            Map<Long, List<SeatDto>> seatTicketCategoryDtoCount = seatDtoList.stream()
                    .collect(Collectors.groupingBy(SeatDto::getTicketCategoryId));
            for (Entry<Long, List<SeatDto>> entry : seatTicketCategoryDtoCount.entrySet()) {
                Long ticketCategoryId = entry.getKey();
                int ticketCount = entry.getValue().size();
                //这里是计算更新票档数据
                JSONObject jsonObject = new JSONObject();
                //票档数量的key
                jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId).getRelKey());
                //票档id
                jsonObject.put("ticketCategoryId", ticketCategoryId);
                //扣减余票数量
                jsonObject.put("ticketCount", ticketCount);
                jsonArray.add(jsonObject);

                JSONObject seatDatajsonObject = new JSONObject();
                //未售卖座位的hash的key
                seatDatajsonObject.put("seatNoSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                //座位数据
                seatDatajsonObject.put("seatDataList", JSON.toJSONString(entry.getValue()));
                addSeatDatajsonArray.add(seatDatajsonObject);
            }
        } else {
            // 不选座：应用层读取 no_sold 座位集合并匹配相邻座位，
            // 匹配算法移到应用层执行（避免 Redis 主线程全量 hvals + 排序阻塞全局）
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();
            //票档校验与余票参数（ticketCategoryId/ticketCount 固定，重试时复用）
            JSONObject jsonObject = new JSONObject();
            //票档数量的key
            jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId).getRelKey());
            //票档id
            jsonObject.put("ticketCategoryId", ticketCategoryId);
            //扣减余票数量
            jsonObject.put("ticketCount", ticketCount);
            jsonArray.add(jsonObject);
            keys.add("1");
        }
        //未售卖座位hash的key(占位符形式)
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH));
        //锁定座位hash的key(占位符形式)
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH));
        keys.add(String.valueOf(programOrderCreateDto.getProgramId()));
        //记录的key(占位符形式)
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_RECORD));
        //记录的标识
        Long identifierId = uidGenerator.getUid();
        //把记录的标识id放进去
        keys.add(RecordType.REDUCE.getValue() + GLIDE_LINE + identifierId + GLIDE_LINE + programOrderCreateDto.getUserId());
        //记录的类型
        keys.add(RecordType.REDUCE.getValue());
        data[0] = JSON.toJSONString(jsonArray);
        data[2] = JSON.toJSONString(programOrderCreateDto.getTicketUserIdList().stream()
                .map(String::valueOf)
                .toList());
        ProgramCacheCreateOrderData programCacheCreateOrderData;
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // 选座：候选座位由用户指定，Lua 单次原子校验+锁定
            data[1] = JSON.toJSONString(addSeatDatajsonArray);
            programCacheCreateOrderData = programCacheCreateOrderResolutionOperate.programCacheOperate(keys, data);
        } else {
            // 不选座：应用层匹配出的候选座位由 Lua 原子校验+锁定；
            // 候选被并发订单抢占（40001/40002/40003）时重新匹配并重试
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();
            programCacheCreateOrderData = null;
            for (int attempt = 0; attempt <= AUTO_MATCH_RETRY_TIMES; attempt++) {
                Map<String, SeatVo> noSoldSeatMap = redisCache.getAllMapForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                                programId, ticketCategoryId), SeatVo.class);
                List<SeatVo> matchedSeatList = matchAdjacentSeats(new ArrayList<>(noSoldSeatMap.values()), ticketCount);
                if (matchedSeatList.size() < ticketCount) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_OCCUPY);
                }
                JSONArray autoMatchSeatDatajsonArray = new JSONArray();
                JSONObject seatDatajsonObject = new JSONObject();
                seatDatajsonObject.put("seatNoSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                seatDatajsonObject.put("seatDataList", JSON.toJSONString(matchedSeatList.stream()
                        .map(seatVo -> {
                            SeatDto seatDto = new SeatDto();
                            seatDto.setId(seatVo.getId());
                            seatDto.setPrice(seatVo.getPrice());
                            seatDto.setTicketCategoryId(seatVo.getTicketCategoryId());
                            return seatDto;
                        }).toList()));
                autoMatchSeatDatajsonArray.add(seatDatajsonObject);
                data[1] = JSON.toJSONString(autoMatchSeatDatajsonArray);
                programCacheCreateOrderData = programCacheCreateOrderResolutionOperate.programCacheOperate(keys, data);
                // 仅对"候选座位被并发抢占"重试；其他错误（余票不足/价格不一致等）直接失败
                if (isSeatRaceError(programCacheCreateOrderData.getCode())) {
                    log.info("自动匹配座位被并发抢占 重试中 节目id : {} 票档id : {} 尝试次数 : {}", programId, ticketCategoryId, attempt + 1);
                    continue;
                }
                break;
            }
        }
        if (!Objects.equals(programCacheCreateOrderData.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new TicketFlowFrameException(Objects.requireNonNull(BaseCode.getRc(programCacheCreateOrderData.getCode())));
        }
        return new CreateOrderTemporaryData(identifierId, programCacheCreateOrderData.getPurchaseSeatList());
    }

    /**
     * 候选座位被并发订单抢占的错误码：座位不存在(40001)、已锁定(40002)、已售出(40003)。
     */
    private boolean isSeatRaceError(Integer code){
        return Objects.equals(code, BaseCode.SEAT_NOT_EXIST.getCode())
                || Objects.equals(code, BaseCode.SEAT_LOCK.getCode())
                || Objects.equals(code, BaseCode.SEAT_SOLD.getCode());
    }

    /**
     * 判断指定票档的座位三区缓存（未售/锁定/已售）是否已预热。
     * 预热时 putHash 创建 hash key；扣减只移动 field 不删除 key，reset 才删除。
     * 因此任一区 hash 存在即可视为已预热，无需全量拉取验证。
     * 必须三区联合判断：仅查 no_sold 会在 no_sold 扣空（hash 自动删除）但 lock 仍有
     * 座位时误判未预热，触发 DB 回写导致已锁座位复活（超卖）。
     */
    private boolean hasSeatResolutionCache(Long programId, Long ticketCategoryId) {
        return Boolean.TRUE.equals(redisCache.hasKey(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId)))
                || Boolean.TRUE.equals(redisCache.hasKey(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId)))
                || Boolean.TRUE.equals(redisCache.hasKey(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId)));
    }

    /**
     * 同步创建订单并发送延迟取消消息。
     * 构建订单参数 → RPC 调 order-service 建单 → 投递延迟队列（超时未支付自动取消）
     */
    private String doCreate(ProgramOrderCreateDto programOrderCreateDto, List<SeatVo> purchaseSeatList, Integer orderVersion) {
        OrderCreateDto orderCreateDto = buildCreateOrderParam(programOrderCreateDto, purchaseSeatList, orderVersion);

        String orderNumber = createOrderByRpc(orderCreateDto, purchaseSeatList);

        DelayOrderCancelDto delayOrderCancelDto = new DelayOrderCancelDto();
        delayOrderCancelDto.setProgramId(programOrderCreateDto.getProgramId());
        delayOrderCancelDto.setOrderNumber(orderCreateDto.getOrderNumber());
        delayOrderCancelSend.sendMessage(delayOrderCancelDto);

        return orderNumber;
    }

    /**
     * 异步创建订单路径。
     * 构建订单参数 → Kafka 发送建单消息 → 投递延迟取消队列。
     * order-service consumer 消费消息后完成实际订单创建。
     */
    private String doCreateV2(ProgramOrderCreateDto programOrderCreateDto,
                              CreateOrderTemporaryData createOrderTemporaryData,
                              Integer orderVersion) {
        OrderCreateDto orderCreateDto = buildCreateOrderParamV2(programOrderCreateDto.getProgramId(),
                programOrderCreateDto.getUserId(), createOrderTemporaryData.getPurchaseSeatList(), orderVersion);
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        BeanUtils.copyProperties(orderCreateDto, orderCreateMq);
        orderCreateMq.setIdentifierId(createOrderTemporaryData.getIdentifierId());
        //插入节目记录任务
        try {
            BusinessThreadPool.execute(() -> createProgramRecordTask(orderCreateMq.getProgramId()));
        } catch (RejectedExecutionException e) {
            // 线程池饱和时降级同步插入：对账记录缺失会使该节目的 Redis 扣减对账失明
            log.error("节目对账记录任务提交失败，降级同步插入 programId : {}", orderCreateMq.getProgramId(), e);
            createProgramRecordTask(orderCreateMq.getProgramId());
        }
        //创建订单
        String orderNumber = createOrderByMq(orderCreateMq, createOrderTemporaryData.getPurchaseSeatList());
        DelayOrderCancelDto delayOrderCancelDto = new DelayOrderCancelDto();
        delayOrderCancelDto.setProgramId(orderCreateDto.getProgramId());
        delayOrderCancelDto.setOrderNumber(orderCreateDto.getOrderNumber());
        delayOrderCancelSend.sendMessage(delayOrderCancelDto);

        return orderNumber;
    }

    /**
     * 创建节目记录任务（异步执行）。
     * 供 ReconciliationTask 对账使用，记录订单变更痕迹。
     *
     * @param programId 节目 ID
     */
    public void createProgramRecordTask(Long programId) {
        ProgramRecordTask programRecordTask = new ProgramRecordTask();
        programRecordTask.setId(uidGenerator.getUid());
        programRecordTask.setProgramId(programId);
        programRecordTask.setCreateTime(DateUtils.now());
        programRecordTask.setEditTime(DateUtils.now());
        programRecordTaskMapper.insert(programRecordTask);
    }

    /**
     * 构建同步路径的订单参数。
     * 从缓存获取节目信息，计算总价，逐购票人组装座位与票价信息。
     */
    private OrderCreateDto buildCreateOrderParam(ProgramOrderCreateDto programOrderCreateDto,
                                                 List<SeatVo> purchaseSeatList,
                                                 Integer orderVersion) {
        ProgramVo programVo = programService.simpleGetProgramAndShowMultipleCache(programOrderCreateDto.getProgramId());
        OrderCreateDto orderCreateDto = new OrderCreateDto();
        orderCreateDto.setOrderNumber(uidGenerator.getOrderNumber(programOrderCreateDto.getUserId()));
        orderCreateDto.setProgramId(programOrderCreateDto.getProgramId());
        orderCreateDto.setProgramItemPicture(programVo.getItemPicture());
        orderCreateDto.setUserId(programOrderCreateDto.getUserId());
        orderCreateDto.setProgramTitle(programVo.getTitle());
        orderCreateDto.setProgramPlace(programVo.getPlace());
        orderCreateDto.setProgramShowTime(programVo.getShowTime());
        orderCreateDto.setProgramPermitChooseSeat(programVo.getPermitChooseSeat());
        BigDecimal databaseOrderPrice =
                purchaseSeatList.stream().map(SeatVo::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        orderCreateDto.setOrderPrice(databaseOrderPrice);
        orderCreateDto.setCreateOrderTime(DateUtils.now());
        orderCreateDto.setOrderVersion(orderVersion);

        List<Long> ticketUserIdList = programOrderCreateDto.getTicketUserIdList();
        // 购票人数与锁定座位数必须一一对应，不一致按业务异常抛出（避免按索引取座位越界）
        if (ticketUserIdList.size() != purchaseSeatList.size()) {
            throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = new ArrayList<>();
        for (int i = 0; i < ticketUserIdList.size(); i++) {
            Long ticketUserId = ticketUserIdList.get(i);
            OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
            orderTicketUserCreateDto.setOrderNumber(orderCreateDto.getOrderNumber());
            orderTicketUserCreateDto.setProgramId(programOrderCreateDto.getProgramId());
            orderTicketUserCreateDto.setUserId(programOrderCreateDto.getUserId());
            orderTicketUserCreateDto.setTicketUserId(ticketUserId);
            SeatVo seatVo =
                    Optional.ofNullable(purchaseSeatList.get(i))
                            .orElseThrow(() -> new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST));
            orderTicketUserCreateDto.setSeatId(seatVo.getId());
            orderTicketUserCreateDto.setSeatInfo(seatVo.getRowCode() + "排" + seatVo.getColCode() + "列");
            orderTicketUserCreateDto.setTicketCategoryId(seatVo.getTicketCategoryId());
            orderTicketUserCreateDto.setOrderPrice(seatVo.getPrice());
            orderTicketUserCreateDto.setCreateOrderTime(DateUtils.now());
            orderTicketUserCreateDtoList.add(orderTicketUserCreateDto);
        }

        orderCreateDto.setOrderTicketUserCreateDtoList(orderTicketUserCreateDtoList);

        return orderCreateDto;
    }

    /**
     * 构建异步路径的订单参数（基于 PurchaseSeat 而非 SeatVo）。
     */
    private OrderCreateDto buildCreateOrderParamV2(Long programId, Long userId, List<PurchaseSeat> purchaseSeatList, Integer orderVersion) {
        ProgramVo programVo = programService.simpleGetProgramAndShowMultipleCache(programId);
        OrderCreateDto orderCreateDto = new OrderCreateDto();
        orderCreateDto.setOrderNumber(uidGenerator.getOrderNumber(userId));
        orderCreateDto.setProgramId(programId);
        orderCreateDto.setProgramItemPicture(programVo.getItemPicture());
        orderCreateDto.setUserId(userId);
        orderCreateDto.setProgramTitle(programVo.getTitle());
        orderCreateDto.setProgramPlace(programVo.getPlace());
        orderCreateDto.setProgramShowTime(programVo.getShowTime());
        orderCreateDto.setProgramPermitChooseSeat(programVo.getPermitChooseSeat());
        BigDecimal databaseOrderPrice =
                purchaseSeatList.stream().map(PurchaseSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        orderCreateDto.setOrderPrice(databaseOrderPrice);
        orderCreateDto.setCreateOrderTime(DateUtils.now());
        orderCreateDto.setOrderVersion(orderVersion);

        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = new ArrayList<>();
        for (PurchaseSeat purchaseSeat : purchaseSeatList) {
            OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
            orderTicketUserCreateDto.setOrderNumber(orderCreateDto.getOrderNumber());
            orderTicketUserCreateDto.setProgramId(programId);
            orderTicketUserCreateDto.setUserId(userId);
            orderTicketUserCreateDto.setTicketUserId(purchaseSeat.getTicketUserId());
            orderTicketUserCreateDto.setSeatId(purchaseSeat.getId());
            orderTicketUserCreateDto.setSeatInfo(purchaseSeat.getRowCode() + "排" + purchaseSeat.getColCode() + "列");
            orderTicketUserCreateDto.setTicketCategoryId(purchaseSeat.getTicketCategoryId());
            orderTicketUserCreateDto.setOrderPrice(purchaseSeat.getPrice());
            orderTicketUserCreateDto.setCreateOrderTime(DateUtils.now());
            orderTicketUserCreateDtoList.add(orderTicketUserCreateDto);
        }
        orderCreateDto.setOrderTicketUserCreateDtoList(orderTicketUserCreateDtoList);
        return orderCreateDto;
    }

    /**
     * 通过 RPC 调用 order-service 创建订单。
     * 失败时自动回滚 Redis 缓存（释放已锁座位、恢复余票）。
     */
    private String createOrderByRpc(OrderCreateDto orderCreateDto, List<SeatVo> purchaseSeatList) {
        ApiResponse<String> createOrderResponse = orderClient.create(orderCreateDto);
        if (!Objects.equals(createOrderResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            log.error("创建订单失败 需人工处理 orderCreateDto : {}", JSON.toJSONString(orderCreateDto));
            updateProgramCacheDataResolution(orderCreateDto.getProgramId(), purchaseSeatList, OrderStatus.CANCEL);
            throw new TicketFlowFrameException(createOrderResponse);
        }
        return createOrderResponse.getData();
    }

    /**
     * 通过 Kafka 发送建单消息。
     * 同步等待发送结果，失败时自动回滚 Redis 缓存。
     */
    private String createOrderByMq(OrderCreateMq orderCreateMq, List<PurchaseSeat> purchaseSeatList) {
        CreateOrderMqDomain createOrderMqDomain = new CreateOrderMqDomain();
        CountDownLatch latch = new CountDownLatch(1);
        createOrderMqDomain.orderNumber = String.valueOf(orderCreateMq.getOrderNumber());
        createOrderSend.sendMessage(JSON.toJSONString(orderCreateMq), sendResult -> {
            log.info("创建订单kafka发送消息成功 topic : {}", sendResult.getRecordMetadata().topic());
            latch.countDown();
        }, ex -> {
            log.error("创建订单kafka发送消息失败 error", ex);
            List<SeatVo> purchaseSeatVoList = purchaseSeatList.stream().map(purchaseSeat -> {
                SeatVo seatVo = new SeatVo();
                BeanUtils.copyProperties(purchaseSeat, seatVo);
                return seatVo;
            }).collect(Collectors.toList());
            try {
                updateProgramCacheDataResolution(orderCreateMq.getProgramId(), purchaseSeatVoList, OrderStatus.CANCEL);
            } catch (Exception rollbackEx) {
                // 回滚失败不能上抛：回调线程异常会跳过下方 countDown，导致调用线程在 await 处永久阻塞
                log.error("创建订单kafka发送失败后回滚缓存异常 需人工处理 programId : {} orderNumber : {}",
                        orderCreateMq.getProgramId(), orderCreateMq.getOrderNumber(), rollbackEx);
            }
            createOrderMqDomain.ticketFlowFrameException = new TicketFlowFrameException(ex);
            latch.countDown();
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                log.error("创建订单kafka发送消息等待超时 orderNumber : {}", orderCreateMq.getOrderNumber());
                throw new TicketFlowFrameException(BaseCode.EXECUTE_TIME_OUT);
            }
        } catch (InterruptedException e) {
            log.error("createOrderByMq InterruptedException", e);
            throw new TicketFlowFrameException(e);
        }
        if (Objects.nonNull(createOrderMqDomain.ticketFlowFrameException)) {
            throw createOrderMqDomain.ticketFlowFrameException;
        }
        return createOrderMqDomain.orderNumber;
    }

    /**
     * 更新 Redis 中的座位与余票数据。
     * 下单时座位从未售区移至锁定区并扣减余票；取消时反向恢复。
     * 通过 Lua 脚本保证多个 Hash 操作的原子性。
     */
    private void updateProgramCacheDataResolution(Long programId, List<SeatVo> seatVoList, OrderStatus orderStatus) {
        if (!(Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode()) ||
                Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()))) {
            throw new TicketFlowFrameException(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
        }
        List<String> keys = new ArrayList<>();
        keys.add("#");

        String[] data = new String[3];
        Map<Long, Long> ticketCategoryCountMap =
                seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getTicketCategoryId, Collectors.counting()));
        JSONArray jsonArray = new JSONArray();
        ticketCategoryCountMap.forEach((k, v) -> {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, k).getRelKey());
            jsonObject.put("ticketCategoryId", String.valueOf(k));
            if (Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode())) {
                jsonObject.put("count", "-" + v);
            } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                jsonObject.put("count", v);
            }
            jsonArray.add(jsonObject);
        });
        Map<Long, List<SeatVo>> seatVoMap =
                seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getTicketCategoryId));
        JSONArray delSeatIdjsonArray = new JSONArray();
        JSONArray addSeatDatajsonArray = new JSONArray();
        seatVoMap.forEach((k, v) -> {
            JSONObject delSeatIdjsonObject = new JSONObject();
            JSONObject seatDatajsonObject = new JSONObject();
            String seatHashKeyDel = "";
            String seatHashKeyAdd = "";
            if (Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode())) {
                seatHashKeyDel = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, k).getRelKey());
                seatHashKeyAdd = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.LOCK.getCode());
                }
            } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                seatHashKeyDel = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
                seatHashKeyAdd = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, k).getRelKey());
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.NO_SOLD.getCode());
                }
            }
            delSeatIdjsonObject.put("seatHashKeyDel", seatHashKeyDel);
            delSeatIdjsonObject.put("seatIdList", v.stream().map(SeatVo::getId).map(String::valueOf).collect(Collectors.toList()));
            delSeatIdjsonArray.add(delSeatIdjsonObject);
            seatDatajsonObject.put("seatHashKeyAdd", seatHashKeyAdd);
            List<String> seatDataList = new ArrayList<>();
            for (SeatVo seatVo : v) {
                seatDataList.add(String.valueOf(seatVo.getId()));
                seatDataList.add(JSON.toJSONString(seatVo));
            }
            seatDatajsonObject.put("seatDataList", seatDataList);
            addSeatDatajsonArray.add(seatDatajsonObject);
        });

        data[0] = JSON.toJSONString(jsonArray);
        data[1] = JSON.toJSONString(delSeatIdjsonArray);
        data[2] = JSON.toJSONString(addSeatDatajsonArray);
        programCacheResolutionOperate.programCacheOperate(keys, data);
    }
}
