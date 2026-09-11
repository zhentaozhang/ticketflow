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
import com.ticketflow.domain.PendingOrder;
import com.ticketflow.domain.PurchaseSeat;
import com.ticketflow.dto.DelayOrderCancelDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.entity.ProgramRecordTask;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ProgramOrderVersion;
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
import com.ticketflow.service.lua.ProgramCacheCreateOrderV5ResolutionOperate;
import com.ticketflow.service.lua.ProgramCacheResolutionOperate;
import com.ticketflow.service.stock.ProgramLocalStockGate;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
     * 不选座自动匹配：候选座位被并发订单抢占时的最大重试次数（超出后抛抢占错误码）。
     * 多实例下本地锁只保证单机串行，跨实例抢占只能靠 Lua 40001-3 裁决 + 重试消化，
     * 重试次数过低会把"系统内部抢座失败"误暴露给用户，故取 3 次。
     */
    private static final int AUTO_MATCH_RETRY_TIMES = 3;

    /**
     * V5 下单幂等标记 TTL（秒）。
     * 必须覆盖"请求不确定窗口"：Lua 扣减成功 → Kafka 发送（最多等待 KAFKA_SEND_AWAIT_MS）→
     * 超时降级已受理（写 PENDING）。客户端在未收到明确失败前可能重试，
     * 若 TTL 短于该窗口，重试会再次走 Lua 成功 → 同一购买意图产生两笔订单（二次扣减/二次计数）。
     * 取 10s：覆盖常见客户端超时重试（秒级~10s），同时保留"合法二次购买"在 10s 后即可提交；
     * 同 orderNumber 的消息重放幂等仍由消费侧（selectOne + 唯一索引 + ORDER_EXIST 特判）兜底。
     * 注意：这是"窗口时长 vs 误伤合法二单"的可调权衡——压测 feeder 用户复用周期需大于本值，
     * 否则开环压测会把"用户复用"误计为 40035 重复提交（见 benchmark README）。
     */
    private static final int V5_IDEMPOTENT_TTL_SECONDS = 10;

    /**
     * V5 不选座自动匹配的窄粒度本地锁前缀（按 programId+ticketCategoryId 加锁）。
     * 仅保护"读 no_sold + 应用层匹配 + Lua 校验"两步操作，选座路径保持无锁。
     */
    private static final String V5_AUTO_MATCH_LOCK = "v5_auto_match";

    /**
     * Kafka 发送建单消息的最大等待时间（毫秒）。
     * 超时降级为"已受理 + PENDING 待确认"（订单终态由对账裁决），
     * 因此无需长时间阻塞请求线程；500ms 已覆盖正常发送，超时路径不影响正确性。
     */
    private static final long KAFKA_SEND_AWAIT_MS = 500L;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private ProgramCacheResolutionOperate programCacheResolutionOperate;

    @Autowired
    ProgramCacheCreateOrderResolutionOperate programCacheCreateOrderResolutionOperate;

    @Autowired
    private ProgramCacheCreateOrderV5ResolutionOperate programCacheCreateOrderV5ResolutionOperate;

    @Autowired
    private ProgramLocalStockGate programLocalStockGate;

    @Autowired
    private com.ticketflow.locallock.LocalLockCache localLockCache;

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
    public List<SeatVo> matchAdjacentSeats(List<SeatVo> seatVoList, int seatCount) {
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
     * V5 全异步创建路径（无锁 + Lua v2 幂等 + 本地库存闸门）。
     * <p>
     * 相比 V4（本地锁 + @RepeatExecuteLimit + V4 Lua）：
     * <ul>
     *   <li>无本地锁：正确性由 Lua 原子性保证，锁只是"快速失败"手段，移除后请求只在 Redis 天然串行，消除 70005；</li>
     *   <li>幂等并入 Lua：SETNX 幂等标记 + 校验 + 扣减在同一 EVAL 内原子完成，请求侧不再需要独立幂等 Redis 往返；</li>
     *   <li>本地库存闸门：售罄请求在到达 Redis 前直接拒绝，售罄短路。</li>
     * </ul>
     *
     * @param programOrderCreateDto 订单创建参数
     * @return 订单编号（Kafka 中预生成）
     */
    public String createNewAsyncV5(ProgramOrderCreateDto programOrderCreateDto) {
        CreateOrderTemporaryData createOrderTemporaryData = createOrderOperateProgramCacheResolutionV5(programOrderCreateDto);
        return doCreateV2(programOrderCreateDto, createOrderTemporaryData, ProgramOrderVersion.V5_VERSION.getValue());
    }

    /**
     * V4 异步路径的锁外发送段（配合 Strategy 在锁内先做 Lua 扣减、锁外再发送）。
     * Lua 扣减已在调用方（锁内）完成，这里只负责构建参数 + Kafka 发送建单消息 + 投递延迟取消队列。
     * 将 Kafka 同步等待发送确认移出锁，可大幅缩短锁持有时间、降低锁竞争失败率。
     *
     * @param programOrderCreateDto    订单创建参数
     * @param createOrderTemporaryData 锁内 Lua 扣减的临时数据（座位/记录标识）
     * @param orderVersion             订单版本号
     * @return 订单编号（Kafka 中预生成）
     */
    public String createNewAsyncAfterLock(ProgramOrderCreateDto programOrderCreateDto,
                                          CreateOrderTemporaryData createOrderTemporaryData,
                                          Integer orderVersion) {
        return doCreateV2(programOrderCreateDto, createOrderTemporaryData, orderVersion);
    }

    /**
     * 确保下单用到的缓存已就绪（座位缓存 + 余票缓存）。
     * <p>
     * <b>必须在进入票档本地锁之前调用。</b>座位缓存是一个票档两万个 field 的全量 Hash，
     * 冷缓存时加载一次要读整张座位表再写进 Redis，是百毫秒级甚至秒级的重活；
     * 而冷缓存正好发生在开票那一下——把它放在锁内做，第一批请求会把本地锁占满，
     * 后面所有请求在 tryLock(3 秒) 上排队失败（70005）。
     * <p>
     * 这两个加载方法内部各自有防并发：
     * {@code seatService.selectSeatResolution} 带 {@code @ServiceLock(Read)} +
     * ReentrantLock(GET_SEAT_LOCK) 双重检查，
     * {@code getRedisRemainNumberResolution} 也带读锁，
     * 所以锁外并发调用是安全的：同一个票档只会有一个请求真的去读库，其余直接命中缓存或走双重检查的快路径。
     *
     * @param programOrderCreateDto 订单创建参数
     */
    public void ensureProgramCacheReady(ProgramOrderCreateDto programOrderCreateDto) {
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programOrderCreateDto.getProgramId());
        for (TicketCategoryVo ticketCategory : getTicketCategoryList(programOrderCreateDto, programShowTime.getShowTime())) {
            ensureTicketCategoryCache(programOrderCreateDto.getProgramId(), ticketCategory.getId(),
                    programShowTime.getShowTime());
        }
    }

    /**
     * 单个票档的缓存就绪检查：缺失才加载，已就绪则只花几次 hasKey 的代价。
     * <p>
     * 锁外（{@link #ensureProgramCacheReady}）和锁内（{@link #createOrderOperateProgramCacheResolution}）都会调它：
     * 锁外是先手，保证正常路径不会在临界区里做重活；锁内那次是兜底，
     * 防止"预热之后、进锁之前"这一小段里缓存又失效（正常路径下这里只会命中缓存，不走加载）。
     */
    private void ensureTicketCategoryCache(Long programId, Long ticketCategoryId, Date showTime) {
        //座位缓存已预热时跳过全量拉取：该 hash 每档 2 万 field，全量读+JSON 反序列化开销大
        if (!hasSeatResolutionCache(programId, ticketCategoryId)) {
            seatService.selectSeatResolution(programId, ticketCategoryId,
                    DateUtils.countBetweenSecond(DateUtils.now(), showTime), TimeUnit.SECONDS);
        }
        //余票缓存已预热时跳过：getRedisRemainNumberResolution 带 @ServiceLock(Read) 分布式读锁，
        //每次调用会获取 Redisson 读锁；返回值此处未使用，仅需确保缓存存在。
        if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId))) {
            ticketCategoryService.getRedisRemainNumberResolution(programId, ticketCategoryId);
        }
    }

    /**
     * 执行 Lua 脚本完成 Redis 缓存原子操作。
     * 构造 Lua 参数 → 原子扣减余票 + 锁定座位 + 写入操作记录。
     * <p>
     * 缓存就绪由调用方负责（{@link #ensureProgramCacheReady}）——正常路径下这里拿到缓存直接扣减；
     * 这里仍保留一次兜底检查，但它是防御性的，不应该成为常规路径。
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
        //锁内兜底：正常路径下 ensureProgramCacheReady 已经把缓存准备好了，这里只会命中缓存
        for (TicketCategoryVo ticketCategory : getTicketCategoryList) {
            ensureTicketCategoryCache(programOrderCreateDto.getProgramId(), ticketCategory.getId(),
                    programShowTime.getShowTime());
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
                //锁定/已售集合的 key：座位被锁后就会从未售集合里删掉，
                //Lua 失败时靠这两个 key 把“已被抢”和“不存在”区分开
                seatDatajsonObject.put("seatLockHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                seatDatajsonObject.put("seatSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
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
                //锁定/已售集合的 key：座位被锁后就会从未售集合里删掉，
                //Lua 失败时靠这两个 key 把“已被抢”和“不存在”区分开
                seatDatajsonObject.put("seatLockHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                seatDatajsonObject.put("seatSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
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
     * V5 版本：执行 Lua v2 完成"幂等守卫 + 校验 + 原子扣减"。
     * 与 V4 方法的差异：
     * <ul>
     *   <li>使用 programDataCreateOrderResolutionV5.lua：幂等 SETNX 并入同一 EVAL（请求侧每单仅 1 次 Lua 往返）；</li>
     *   <li>执行前做本地库存闸门预判，售罄请求不进入 Redis；</li>
     *   <li>成功后用 Lua 返回的扣减后余票更新本地库存闸门。</li>
     * </ul>
     */
    public CreateOrderTemporaryData createOrderOperateProgramCacheResolutionV5(ProgramOrderCreateDto programOrderCreateDto) {
        //从多级缓存中查找节目演出时间ProgramShowTime
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programOrderCreateDto.getProgramId());
        //查询对应的票档类型
        List<TicketCategoryVo> getTicketCategoryList =
                getTicketCategoryList(programOrderCreateDto, programShowTime.getShowTime());
        //遍历得到的票档，缓存未预热时才预热（预热后每单零 Redis 读取）
        for (TicketCategoryVo ticketCategory : getTicketCategoryList) {
            Long ticketCategoryId = ticketCategory.getId();
            if (!hasSeatResolutionCache(programOrderCreateDto.getProgramId(), ticketCategoryId)) {
                seatService.selectSeatResolution(programOrderCreateDto.getProgramId(), ticketCategoryId,
                        DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);
            }
            if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programOrderCreateDto.getProgramId(), ticketCategoryId))) {
                ticketCategoryService.getRedisRemainNumberResolution(
                        programOrderCreateDto.getProgramId(), ticketCategoryId);
            }
        }
        Long programId = programOrderCreateDto.getProgramId();
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<String> keys = new ArrayList<>();
        //V5：ARGV[4]=幂等标记 TTL（秒）、ARGV[5]=限购数量、ARGV[6]=本次购票总数；data[0..2] 与 V4 一致
        String[] data = new String[6];
        JSONArray jsonArray = new JSONArray();
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
            // 不选座：应用层读取 no_sold 座位集合并匹配相邻座位
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
        //V5：幂等 key（同一 userId+programId 的提交标记）
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.V5_ORDER_CREATE_IDEMPOTENT,
                programOrderCreateDto.getUserId(), programOrderCreateDto.getProgramId()).getRelKey());
        //V5：账户订单计数 key（限购校验 + 扣减成功后累加，计数单一归属 Lua）
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
                programOrderCreateDto.getUserId(), programOrderCreateDto.getProgramId()).getRelKey());
        data[0] = JSON.toJSONString(jsonArray);
        data[2] = JSON.toJSONString(programOrderCreateDto.getTicketUserIdList().stream()
                .map(String::valueOf)
                .toList());
        data[3] = String.valueOf(V5_IDEMPOTENT_TTL_SECONDS);
        //V5 限购：perAccountLimitPurchaseCount（缓存中未配置/为空按不限购处理），null -> "0"
        Integer perAccountLimitPurchaseCount =
                programService.simpleGetProgramAndShowMultipleCache(programId).getPerAccountLimitPurchaseCount();
        data[4] = String.valueOf(Objects.isNull(perAccountLimitPurchaseCount) ? 0 : perAccountLimitPurchaseCount);
        //V5 限购：本次购票总数（选座=座位数，不选座=票数）
        data[5] = String.valueOf(CollectionUtil.isNotEmpty(seatDtoList) ? seatDtoList.size()
                : programOrderCreateDto.getTicketCount());

        //本地库存闸门预判：快速拒绝已售罄，避免无谓的 Redis EVAL
        checkLocalStockGate(programOrderCreateDto);

        ProgramCacheCreateOrderData programCacheCreateOrderData;
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // 选座：候选座位由用户指定，Lua 单次原子校验+锁定（无锁，Redis 天然串行）
            data[1] = JSON.toJSONString(addSeatDatajsonArray);
            programCacheCreateOrderData = programCacheCreateOrderV5ResolutionOperate.programCacheOperate(keys, data);
        } else {
            // 不选座：应用层匹配候选座位，候选被并发抢占（40001/40002/40003）时重新匹配重试。
            // V5 整体无锁，但"读取 no_sold + 应用层匹配 + Lua 校验"是两步操作，同一票档的并发自动匹配
            // 会选中同一批相邻座位互相抢占（40001 风暴）。因此仅对自动匹配路径加窄粒度本地锁
            // （按 programId+ticketCategoryId），选座路径保持无锁。
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();
            ReentrantLock autoMatchLock = localLockCache.getLock(
                    V5_AUTO_MATCH_LOCK + "-" + programId + "-" + ticketCategoryId, false);
            autoMatchLock.lock();
            try {
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
                    programCacheCreateOrderData = programCacheCreateOrderV5ResolutionOperate.programCacheOperate(keys, data);
                    // 仅对"候选座位被并发抢占"重试；其他错误（重复提交/余票不足/价格不一致等）直接失败
                    if (isSeatRaceError(programCacheCreateOrderData.getCode())) {
                        log.info("自动匹配座位被并发抢占 重试中 节目id : {} 票档id : {} 尝试次数 : {}", programId, ticketCategoryId, attempt + 1);
                        continue;
                    }
                    break;
                }
            } finally {
                autoMatchLock.unlock();
            }
        }
        if (!Objects.equals(programCacheCreateOrderData.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new TicketFlowFrameException(Objects.requireNonNull(BaseCode.getRc(programCacheCreateOrderData.getCode())));
        }
        //Lua 成功：用返回的扣减后余票更新本地库存闸门（精确追踪，无需额外 Redis 读取）
        if (CollectionUtil.isNotEmpty(programCacheCreateOrderData.getRemainMap())) {
            programCacheCreateOrderData.getRemainMap().forEach((categoryId, remain) ->
                    programLocalStockGate.track(programId, Long.valueOf(categoryId), remain));
        }
        return new CreateOrderTemporaryData(identifierId, programCacheCreateOrderData.getPurchaseSeatList());
    }

    /**
     * 本地库存闸门预判：对应票档已跟踪且预估余票不足时直接拒绝，不进入 Redis。
     * 闸门未跟踪（-1）或闸门有货时放行，最终正确性由 Lua 裁决。
     */
    private void checkLocalStockGate(ProgramOrderCreateDto programOrderCreateDto) {
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            Map<Long, Long> countMap = seatDtoList.stream()
                    .collect(Collectors.groupingBy(SeatDto::getTicketCategoryId, Collectors.counting()));
            for (Entry<Long, Long> entry : countMap.entrySet()) {
                int estimated = programLocalStockGate.estimatedRemain(programOrderCreateDto.getProgramId(), entry.getKey());
                if (estimated >= 0 && estimated < entry.getValue().intValue()) {
                    throw new TicketFlowFrameException(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT);
                }
            }
        } else {
            int estimated = programLocalStockGate.estimatedRemain(
                    programOrderCreateDto.getProgramId(), programOrderCreateDto.getTicketCategoryId());
            if (estimated >= 0 && estimated < programOrderCreateDto.getTicketCount()) {
                throw new TicketFlowFrameException(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT);
            }
        }
    }

    /**
     * 候选座位被并发订单抢占的错误码：座位不存在(40001)、已锁定(40002)、已售出(40003)。
     */
    private boolean isSeatRaceError(Integer code) {
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
     * <p>
     * Redis Lua 完成座位锁定和余票扣减后进入这里：
     * 1. 构建订单参数
     * 2. 异步写入节目对账记录
     * 3. 发送 Kafka 建单消息，由 order-service 异步落库
     * 4. 投递延迟取消消息，超时未支付后自动取消订单
     */
    private String doCreateV2(ProgramOrderCreateDto programOrderCreateDto,
                              CreateOrderTemporaryData createOrderTemporaryData,
                              Integer orderVersion) {

        // 第一阶段：构建订单参数。
        // 此时 Redis 中的座位已经锁定成功，这里根据用户、节目、座位等信息
        // 组装真正要创建的订单数据，同时生成订单号。
        OrderCreateDto orderCreateDto = buildCreateOrderParamV2(
                programOrderCreateDto.getProgramId(),
                programOrderCreateDto.getUserId(),
                createOrderTemporaryData.getPurchaseSeatList(),
                orderVersion);

        // 将订单参数转换成 Kafka 建单消息，并补充本次 Redis 操作对应的唯一标识。
        // identifierId 后续用于节目库存变更、消息记录以及对账定位。
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        BeanUtils.copyProperties(orderCreateDto, orderCreateMq);
        orderCreateMq.setIdentifierId(createOrderTemporaryData.getIdentifierId());

        // 第二阶段：异步写入节目对账记录。
        // Redis Lua 已经完成座位锁定和余票扣减，需要留下对应的变更记录，
        // 后续可以根据这条记录校验 Redis 与数据库之间的数据是否一致。
        //
        // 正常情况下异步执行，不阻塞当前下单线程；
        // 如果线程池已经饱和，则降级为同步执行，避免对账记录直接丢失。
        try {
            BusinessThreadPool.execute(
                    () -> createProgramRecordTask(orderCreateMq.getProgramId()));
        } catch (RejectedExecutionException e) {
            log.error("节目对账记录任务提交失败，降级同步插入 programId : {}",
                    orderCreateMq.getProgramId(), e);
            createProgramRecordTask(orderCreateMq.getProgramId());
        }

        // 第三阶段：发送 Kafka 建单消息。
        // 当前线程只负责把建单请求发送到 Kafka，不直接操作订单数据库。
        // 后续由 order-service 消费消息，完成订单、购票人等数据的实际落库。
        String orderNumber = createOrderByMq(
                orderCreateMq,
                createOrderTemporaryData.getPurchaseSeatList());

        // 第四阶段：投递延迟取消消息。
        // 订单虽然已经进入异步创建流程，但此时用户还没有完成支付，
        // 因此需要同时设置超时取消机制，避免锁定的座位和余票长期占用。
        DelayOrderCancelDto delayOrderCancelDto = new DelayOrderCancelDto();
        delayOrderCancelDto.setProgramId(orderCreateDto.getProgramId());
        delayOrderCancelDto.setOrderNumber(orderCreateDto.getOrderNumber());

        // 到达指定延迟时间后，由延迟队列消费者触发订单取消，
        // 进一步执行座位释放、余票回补等操作。
        delayOrderCancelSend.sendMessage(delayOrderCancelDto);

        // 返回订单号，供上层流程继续处理。
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
     * 等待发送确认；超时（KAFKA_SEND_AWAIT_MS）降级为已受理并写 PENDING 待确认队列（不抛异常），
     * 订单终态由 PENDING 对账任务裁决；发送失败仍回滚 Redis 缓存并上抛。
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
                // 幂等保护：仅当座位仍在锁定集合时才回滚。发送超时已降级为已受理（写 PENDING），
                // PENDING 补偿可能已先回滚（幂等）；若这里仍无条件反向恢复，会把余票二次回补（超卖）。
                if (isSeatStillLocked(orderCreateMq, purchaseSeatVoList)) {
                    updateProgramCacheDataResolution(orderCreateMq.getProgramId(), purchaseSeatVoList, OrderStatus.CANCEL);
                    // V5：正向 Lua 扣减成功时已 INCRBY 限购计数，回滚成功后对称减回，
                    // 避免"发送失败被回滚的订单"永久占用用户限购配额（与正常取消路径语义对齐）。
                    if (Objects.equals(orderCreateMq.getOrderVersion(), ProgramOrderVersion.V5_VERSION.getValue())) {
                        decrementAccountOrderCount(orderCreateMq, purchaseSeatVoList.size());
                    }
                } else {
                    log.info("创建订单kafka发送失败但座位已不在锁定状态 跳过回滚 orderNumber : {}",
                            orderCreateMq.getOrderNumber());
                }
            } catch (Exception rollbackEx) {
                // 回滚失败不能上抛：回调线程异常会跳过下方 countDown，导致调用线程在 await 处永久阻塞。
                // 写入 DISCARD_ORDER 留痕，由对账任务 discardOrderCompensation（幂等回滚 + V5 计数减回）兜底，
                // 避免"库存已扣、订单未建、回滚也未成功"的库存黑洞。
                log.error("创建订单kafka发送失败后回滚缓存异常 写入DISCARD_ORDER待对账 programId : {} orderNumber : {}",
                        orderCreateMq.getProgramId(), orderCreateMq.getOrderNumber(), rollbackEx);
                try {
                    redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                            orderCreateMq.getProgramId()), buildRollbackFailDiscardOrder(orderCreateMq, rollbackEx));
                } catch (Exception discardEx) {
                    log.error("写入DISCARD_ORDER失败 需人工处理 programId : {} orderNumber : {}",
                            orderCreateMq.getProgramId(), orderCreateMq.getOrderNumber(), discardEx);
                }
            }
            createOrderMqDomain.ticketFlowFrameException = new TicketFlowFrameException(ex);
            latch.countDown();
        });
        try {
            if (!latch.await(KAFKA_SEND_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                // 超时降级：不再抛异常。消息大概率已发送（producer retries=3），
                // 订单终态由 PENDING 对账兜底：已建单则移除，未建单则回滚 Redis 座位。
                log.warn("创建订单kafka发送消息等待超时 降级为已受理 orderNumber : {}", orderCreateMq.getOrderNumber());
                writePendingOrder(orderCreateMq);
                return createOrderMqDomain.orderNumber;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断视为已受理（同超时语义），不抛异常
            log.error("createOrderByMq InterruptedException 降级为已受理 orderNumber : {}", orderCreateMq.getOrderNumber(), e);
            writePendingOrder(orderCreateMq);
            return createOrderMqDomain.orderNumber;
        }
        if (Objects.nonNull(createOrderMqDomain.ticketFlowFrameException)) {
            throw createOrderMqDomain.ticketFlowFrameException;
        }
        return createOrderMqDomain.orderNumber;
    }

    /**
     * 写入 PENDING 待确认队列：请求侧发送超时降级时留痕，
     * 供 order-service 对账任务扫描后裁决（建单成功移除 / 未建单回滚座位）。
     */
    private void writePendingOrder(OrderCreateMq orderCreateMq) {
        try {
            redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_CREATE_PENDING,
                    orderCreateMq.getProgramId()), new PendingOrder(orderCreateMq));
        } catch (Exception e) {
            // PENDING 写入失败不能上抛：主路径已降级为成功，缺失留痕由 Redis 座位锁死时限 + 人工对账兜底
            log.error("创建订单kafka发送超时后写入 PENDING 失败 需人工处理 orderNumber : {}",
                    orderCreateMq.getOrderNumber(), e);
        }
    }

    /**
     * 判断订单座位是否仍处于 Redis 锁定集合（发送失败回调的回滚守卫）。
     * <p>
     * 场景：发送超时降级为已受理（写 PENDING）后，PENDING 补偿可能已把座位回滚（幂等），
     * 若迟到的失败回调仍无条件反向恢复，余票会被二次回补（超卖）。
     * 因此回滚前先确认至少有一个座位仍在锁定集合，不在则说明已被其他路径回滚，跳过。
     */
    private boolean isSeatStillLocked(OrderCreateMq orderCreateMq, List<SeatVo> purchaseSeatVoList) {
        try {
            Map<Long, List<SeatVo>> seatVoMap = purchaseSeatVoList.stream()
                    .collect(Collectors.groupingBy(SeatVo::getTicketCategoryId));
            for (Entry<Long, List<SeatVo>> entry : seatVoMap.entrySet()) {
                List<SeatVo> lockedSeats = redisCache.multiGetForHash(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,
                                orderCreateMq.getProgramId(), entry.getKey()),
                        entry.getValue().stream().map(SeatVo::getId).map(String::valueOf).collect(Collectors.toList()),
                        SeatVo.class);
                if (CollectionUtil.isNotEmpty(lockedSeats)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 查询失败时保守回滚（多数场景座位仍在锁定集合），回滚 Lua 本身 hdel/hset 幂等
            log.warn("查询座位锁定状态失败 按保守回滚处理 orderNumber : {}", orderCreateMq.getOrderNumber(), e);
            return true;
        }
        return false;
    }

    /**
     * V5 回滚后对称减回限购计数（正向 Lua INCRBY 的反向操作）。
     * 仅限购计数，不影响余票/座位（由回滚 Lua 负责）。
     */
    private void decrementAccountOrderCount(OrderCreateMq orderCreateMq, int ticketCount) {
        try {
            redisCache.incrBy(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
                    orderCreateMq.getUserId(), orderCreateMq.getProgramId()), -ticketCount);
        } catch (Exception e) {
            log.error("V5 回滚减回限购计数失败 需人工处理 orderNumber : {}", orderCreateMq.getOrderNumber(), e);
        }
    }

    /**
     * 构建"回滚失败待补偿订单"（JSON 结构对齐 order-service 的 DiscardOrder：
     * orderCreateMq / discardOrderReason / errorMsg 三个字段），
     * 由对账任务 discardOrderCompensation 反序列化后执行幂等回滚 + V5 计数减回。
     */
    private Map<String, Object> buildRollbackFailDiscardOrder(OrderCreateMq orderCreateMq, Exception rollbackEx) {
        Map<String, Object> discardOrder = new HashMap<>(4);
        discardOrder.put("orderCreateMq", orderCreateMq);
        discardOrder.put("discardOrderReason", DiscardOrderReason.ROLLBACK_FAIL.getCode());
        discardOrder.put("errorMsg", String.valueOf(rollbackEx.getMessage()));
        return discardOrder;
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
