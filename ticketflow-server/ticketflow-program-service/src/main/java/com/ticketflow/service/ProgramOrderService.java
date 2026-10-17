package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
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
import com.ticketflow.enums.RecordType;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ProgramRecordTaskMapper;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.cache.CacheTtlUtil;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
     * 不选座自动匹配：候选座位被并发订单抢占时的最大重试次数（超出后抛抢占错误码）。
     * 多实例下本地锁只保证单机串行，跨实例抢占只能靠 Lua 40001-3 裁决 + 重试消化，
     * 重试次数过低会把"系统内部抢座失败"误暴露给用户，故取 3 次。
     */
    private static final int AUTO_MATCH_RETRY_TIMES = 3;

    /**
     * 座位排序规则：先排号再列号。抽成常量，保证自动匹配路径只排序一次。
     */
    private static final Comparator<SeatVo> SEAT_ROW_COL_COMPARATOR =
            Comparator.comparing(SeatVo::getRowCode).thenComparing(SeatVo::getColCode);

    /**
     * Kafka 发送建单消息的最大等待时间（毫秒）。
     * 超时降级为"已受理 + PENDING 待确认"（订单终态由对账裁决），
     * 因此无需长时间阻塞请求线程；500ms 已覆盖正常发送，超时路径不影响正确性。
     */
    private static final long KAFKA_SEND_AWAIT_MS = 500L;

    /**
     * 对账记录任务专用线程池：与通用 BusinessThreadPool 隔离。
     * 下单每笔都会投递一个对账记录任务，高频且必须落库（否则对账缺失），
     * 混用通用池会在尖峰期被其打满并抛异常，进而在下单线程同步插库、拖垮秒杀 RT。
     * 独立池 + 有界队列 + CallerRunsPolicy（饱和时由提交线程亲自执行，形成背压，绝不丢任务）。
     */
    private static final ThreadPoolExecutor PROGRAM_RECORD_TASK_EXECUTOR = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            runnable -> {
                Thread thread = new Thread(runnable, "program-record-task");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

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
    public List<SeatVo> matchAdjacentSeats(List<SeatVo> seatVoList, int seatCount) {
        if (CollectionUtil.isEmpty(seatVoList) || seatVoList.size() < seatCount) {
            return new ArrayList<>();
        }
        List<SeatVo> sortedSeatList = seatVoList.stream()
                .sorted(SEAT_ROW_COL_COMPARATOR)
                .toList();
        return matchAdjacentSeatsSorted(sortedSeatList, seatCount);
    }

    /**
     * 在<b>已按排号/列号排序</b>的座位列表上做滑动窗口匹配。
     * 自动匹配路径会复用同一份排序结果（并发抢占时只剔除被抢座位再匹配），
     * 避免每次重试都重排整档座位。
     *
     * @param sortedSeatList 已排序的未售座位列表
     * @param seatCount      需要匹配的座位数量
     * @return 匹配到的相邻座位；不足时返回空列表
     */
    private List<SeatVo> matchAdjacentSeatsSorted(List<SeatVo> sortedSeatList, int seatCount) {
        if (CollectionUtil.isEmpty(sortedSeatList) || sortedSeatList.size() < seatCount) {
            return new ArrayList<>();
        }
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
     * 在轻量排级座位映射中寻找单排内连续相邻的 seatCount 个座位。
     * 避免大 Hash 全量拉取与排序，内存中耗时 < 0.1ms。
     */
    private List<SeatVo> matchAdjacentSeatsInRowMap(Map<String, List<SeatVo>> rowSeatMap, int seatCount) {
        if (CollectionUtil.isEmpty(rowSeatMap)) {
            return new ArrayList<>();
        }
        for (Map.Entry<String, List<SeatVo>> entry : rowSeatMap.entrySet()) {
            List<SeatVo> rowList = entry.getValue();
            if (CollectionUtil.isEmpty(rowList) || rowList.size() < seatCount) {
                continue;
            }
            // 排内滑动窗口寻找连续座位
            for (int i = 0; i <= rowList.size() - seatCount; i++) {
                boolean adjacent = true;
                for (int j = 0; j < seatCount - 1; j++) {
                    SeatVo current = rowList.get(i + j);
                    SeatVo next = rowList.get(i + j + 1);
                    if (next.getColCode() - current.getColCode() != 1) {
                        adjacent = false;
                        break;
                    }
                }
                if (adjacent) {
                    return new ArrayList<>(rowList.subList(i, i + seatCount));
                }
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
        List<SeatVo> purchaseSeatList = createOrderTemporaryData.getPurchaseSeatList().stream()
                .map(this::convertToSeatVo)
                .collect(Collectors.toList());
        return doCreate(programOrderCreateDto, purchaseSeatList, orderVersion);
    }

    private SeatVo convertToSeatVo(PurchaseSeat purchaseSeat) {
        if (purchaseSeat == null) {
            return null;
        }
        SeatVo seatVo = new SeatVo();
        seatVo.setId(purchaseSeat.getId());
        seatVo.setProgramId(purchaseSeat.getProgramId());
        seatVo.setTicketCategoryId(purchaseSeat.getTicketCategoryId());
        seatVo.setRowCode(purchaseSeat.getRowCode());
        seatVo.setColCode(purchaseSeat.getColCode());
        seatVo.setSeatType(purchaseSeat.getSeatType());
        seatVo.setSeatTypeName(purchaseSeat.getSeatTypeName());
        seatVo.setPrice(purchaseSeat.getPrice());
        seatVo.setSellStatus(purchaseSeat.getSellStatus());
        seatVo.setTicketUserId(purchaseSeat.getTicketUserId());
        return seatVo;
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
        //一次 pipeline 拿齐座位三区 + 余票共 4 个 key 的存在性，替代最多 4 次串行 hasKey（每单省 1~3 次 Redis 往返）
        List<Boolean> existsList = redisCache.hasKeys(List.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId),
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId),
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId),
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId)));
        //座位三区任一存在即视为已预热，跳过全量拉取：该 hash 每档 2 万 field，全量读+JSON 反序列化开销大。
        //必须三区联合判断：仅查 no_sold 会在 no_sold 扣空（hash 自动删除）但 lock 仍有座位时误判未预热，
        //触发 DB 回写导致已锁座位复活（超卖）。
        boolean seatCached = Boolean.TRUE.equals(existsList.get(0))
                || Boolean.TRUE.equals(existsList.get(1))
                || Boolean.TRUE.equals(existsList.get(2));
        if (!seatCached) {
            seatService.selectSeatResolution(programId, ticketCategoryId,
                    CacheTtlUtil.calculateExpireSeconds(showTime), TimeUnit.SECONDS);
        }
        //余票缓存已预热时跳过：getRedisRemainNumberResolution 带 @ServiceLock(Read) 分布式读锁，
        //每次调用会获取 Redisson 读锁；返回值此处未使用，仅需确保缓存存在。
        if (!Boolean.TRUE.equals(existsList.get(3))) {
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
        List<Long> ticketUserIdList = programOrderCreateDto.getTicketUserIdList();
        int currentPurchaseCount = CollectionUtil.isNotEmpty(seatDtoList) ? seatDtoList.size() : programOrderCreateDto.getTicketCount();

        ProgramVo programVo = programService.simpleGetByIdMultipleCache(programId);
        Integer perAccountLimit = (programVo != null && programVo.getPerAccountLimitPurchaseCount() != null)
                ? programVo.getPerAccountLimitPurchaseCount() : 0;
        String accountOrderCountKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.ACCOUNT_ORDER_COUNT, programOrderCreateDto.getUserId(), programId).getRelKey();

        List<String> keys = new ArrayList<>();
        String[] data = new String[5];
        //更新票档数据集合
        JSONArray jsonArray = new JSONArray();
        //添加座位数据集合
        JSONArray addSeatDatajsonArray = new JSONArray();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            keys.add("1");
            // 显式绑定购票人与座位，解决跨票档选座错配问题
            for (int i = 0; i < seatDtoList.size(); i++) {
                seatDtoList.get(i).setTicketUserId(ticketUserIdList.get(i));
            }
            Map<Long, List<SeatDto>> seatTicketCategoryDtoCount = seatDtoList.stream()
                    .collect(Collectors.groupingBy(SeatDto::getTicketCategoryId, java.util.LinkedHashMap::new, Collectors.toList()));
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
            // 不选座：应用层参数构造
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId).getRelKey());
            jsonObject.put("ticketCategoryId", ticketCategoryId);
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
        //第8个key: 单账号限购key
        keys.add(accountOrderCountKey);

        data[0] = JSON.toJSONString(jsonArray);
        data[2] = JSON.toJSONString(programOrderCreateDto.getTicketUserIdList().stream()
                .map(String::valueOf)
                .toList());
        data[3] = String.valueOf(perAccountLimit);
        data[4] = String.valueOf(currentPurchaseCount);

        ProgramCacheCreateOrderData programCacheCreateOrderData;
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // 选座：候选座位由用户指定，Lua 单次原子校验+锁定
            data[1] = JSON.toJSONString(addSeatDatajsonArray);
            programCacheCreateOrderData = programCacheCreateOrderResolutionOperate.programCacheOperate(keys, data);
        } else {
            // 不选座：利用轻量排级索引在内存中极速滑动匹配（彻底消灭全量 20,000 座位传输）
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();
            Map<String, List<SeatVo>> rowSeatMap = seatService.getRowSeatIndex(programId, ticketCategoryId);
            if (CollectionUtil.isEmpty(rowSeatMap)) {
                seatService.selectSeatResolution(programId, ticketCategoryId,
                        CacheTtlUtil.calculateExpireSeconds(programShowTime.getShowTime()), TimeUnit.SECONDS);
                rowSeatMap = seatService.getRowSeatIndex(programId, ticketCategoryId);
            }
            // 复制一份用于本地并发重试剔除
            Map<String, List<SeatVo>> localRowSeatMap = new java.util.LinkedHashMap<>();
            if (CollectionUtil.isNotEmpty(rowSeatMap)) {
                rowSeatMap.forEach((k, v) -> localRowSeatMap.put(k, new ArrayList<>(v)));
            }

            programCacheCreateOrderData = null;
            for (int attempt = 0; attempt <= AUTO_MATCH_RETRY_TIMES; attempt++) {
                List<SeatVo> matchedSeatList = matchAdjacentSeatsInRowMap(localRowSeatMap, ticketCount);
                if (matchedSeatList.size() < ticketCount) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_OCCUPY);
                }
                JSONArray autoMatchSeatDatajsonArray = new JSONArray();
                JSONObject seatDatajsonObject = new JSONObject();
                seatDatajsonObject.put("seatNoSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                seatDatajsonObject.put("seatLockHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                seatDatajsonObject.put("seatSoldHashKey", RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());

                List<SeatDto> matchedSeatDtoList = new ArrayList<>(matchedSeatList.size());
                for (int i = 0; i < matchedSeatList.size(); i++) {
                    SeatVo seatVo = matchedSeatList.get(i);
                    SeatDto seatDto = new SeatDto();
                    seatDto.setId(seatVo.getId());
                    seatDto.setPrice(seatVo.getPrice());
                    seatDto.setTicketCategoryId(seatVo.getTicketCategoryId());
                    seatDto.setTicketUserId(ticketUserIdList.get(i));
                    matchedSeatDtoList.add(seatDto);
                }
                seatDatajsonObject.put("seatDataList", JSON.toJSONString(matchedSeatDtoList));
                autoMatchSeatDatajsonArray.add(seatDatajsonObject);
                data[1] = JSON.toJSONString(autoMatchSeatDatajsonArray);
                programCacheCreateOrderData = programCacheCreateOrderResolutionOperate.programCacheOperate(keys, data);
                // 仅对"候选座位被并发抢占"重试；其他错误（余票不足/价格不一致/限购等）直接失败
                if (isSeatRaceError(programCacheCreateOrderData.getCode())) {
                    // 本地排级剔除被抢占座位，继续下一轮快速匹配
                    for (SeatVo seatVo : matchedSeatList) {
                        List<SeatVo> rowList = localRowSeatMap.get(String.valueOf(seatVo.getRowCode()));
                        if (rowList != null) {
                            rowList.removeIf(s -> Objects.equals(s.getId(), seatVo.getId()));
                        }
                    }
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
            PROGRAM_RECORD_TASK_EXECUTOR.execute(
                    () -> createProgramRecordTask(orderCreateMq.getProgramId()));
        } catch (Exception e) {
            log.warn("节目对账记录任务提交失败，跳过主线程同步插入 programId : {}",
                    orderCreateMq.getProgramId(), e);
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
        for (SeatVo seatVo : purchaseSeatList) {
            if (seatVo == null) {
                throw new TicketFlowFrameException(BaseCode.SEAT_NOT_EXIST);
            }
            OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
            orderTicketUserCreateDto.setOrderNumber(orderCreateDto.getOrderNumber());
            orderTicketUserCreateDto.setProgramId(programOrderCreateDto.getProgramId());
            orderTicketUserCreateDto.setUserId(programOrderCreateDto.getUserId());
            orderTicketUserCreateDto.setTicketUserId(seatVo.getTicketUserId());
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
            rollbackAccountLimit(orderCreateDto.getUserId(), orderCreateDto.getProgramId(), purchaseSeatList.size());
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
        java.util.concurrent.atomic.AtomicBoolean degradedToPending = new java.util.concurrent.atomic.AtomicBoolean(false);
        createOrderMqDomain.orderNumber = String.valueOf(orderCreateMq.getOrderNumber());
        createOrderSend.sendMessage(JSON.toJSONString(orderCreateMq), sendResult -> {
            log.info("创建订单kafka发送消息成功 topic : {}", sendResult.getRecordMetadata().topic());
            latch.countDown();
        }, ex -> {
            log.error("创建订单kafka发送消息失败 error", ex);
            if (degradedToPending.get()) {
                // 超时状态机拦截：主线程已超时降级为"已受理并写入 PENDING"，
                // 严禁在此就地回滚 Redis 座位！避免导致用户已下单但库存被放跑，统一交由 PENDING 对账任务最终安全裁决
                log.warn("Kafka在超时降级后收到失败响应，交由PENDING对账任务处理，跳过即时回滚 orderNumber: {}",
                        orderCreateMq.getOrderNumber(), ex);
                latch.countDown();
                return;
            }
            List<SeatVo> purchaseSeatVoList = purchaseSeatList.stream().map(purchaseSeat -> {
                SeatVo seatVo = new SeatVo();
                BeanUtils.copyProperties(purchaseSeat, seatVo);
                return seatVo;
            }).collect(Collectors.toList());
            try {
                // 幂等保护：仅当座位仍在锁定集合时才回滚。
                if (isSeatStillLocked(orderCreateMq, purchaseSeatVoList)) {
                    updateProgramCacheDataResolution(orderCreateMq.getProgramId(), purchaseSeatVoList, OrderStatus.CANCEL);
                    rollbackAccountLimit(orderCreateMq.getUserId(), orderCreateMq.getProgramId(), purchaseSeatVoList.size());
                } else {
                    log.info("创建订单kafka发送失败但座位已不在锁定状态 跳过回滚 orderNumber : {}",
                            orderCreateMq.getOrderNumber());
                }
            } catch (Exception rollbackEx) {
                // 回滚失败不能上抛：写入 DISCARD_ORDER 留痕，由对账任务 discardOrderCompensation 兜底
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
                // 超时降级接管
                degradedToPending.set(true);
                log.warn("创建订单kafka发送消息等待超时 降级为已受理 orderNumber : {}", orderCreateMq.getOrderNumber());
                writePendingOrder(orderCreateMq);
                return createOrderMqDomain.orderNumber;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            degradedToPending.set(true);
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
     * 安全回退单账号限购配额（带下限保底防负数）。
     */
    private void rollbackAccountLimit(Long userId, Long programId, int count) {
        if (userId == null || programId == null || count <= 0) {
            return;
        }
        try {
            com.ticketflow.redis.RedisKeyBuild key = RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, programId);
            Object obj = redisCache.get(key, Object.class);
            if (obj != null) {
                long cur = Long.parseLong(String.valueOf(obj));
                long newVal = Math.max(0, cur - count);
                redisCache.set(key, newVal, 30, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.warn("回退单账号限购配额异常 userId:{} programId:{}", userId, programId, e);
        }
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
     * 构建"回滚失败待补偿订单"（JSON 结构对齐 order-service 的 DiscardOrder：
     * orderCreateMq / discardOrderReason / errorMsg 三个字段），
     * 由对账任务 discardOrderCompensation 反序列化后执行幂等回滚。
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
