package com.ticketflow.service.composite.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.ticketflow.client.OrderClient;
import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.ProgramService;
import com.ticketflow.service.composite.AbstractProgramCheckHandler;
import com.ticketflow.service.tool.TokenExpireManager;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.TicketUserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户有效性校验（Composite BFS 第二层校验节点）。
 * 校验顺序：executeTier=2, executeOrder=2。
 * 通过 UserClient Feign 调用 user-service 验证购票人存在性 + 状态，
 * 同时缓存 userId → ticketUserList 防止短时间内重复 RPC。
 */
@Slf4j
@Component
public class ProgramUserExistCheckHandler extends AbstractProgramCheckHandler {
    
    @Autowired
    private UserClient userClient;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private OrderClient orderClient;
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private TokenExpireManager tokenExpireManager;
    
    /**
     * 验证购票人有效性：先查本地缓存，未命中则通过 UserClient RPC 获取；
     * 再校验每个 ticketUserId 是否属于当前用户；最后确认节目存在。
     *
     * @param programOrderCreateDto 订单创建参数
     */
    @Override
    protected void execute(ProgramOrderCreateDto programOrderCreateDto) {
        List<TicketUserVo> ticketUserVoList = redisCache.getValueIsList(RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_USER_LIST, programOrderCreateDto.getUserId()), TicketUserVo.class);
        if (CollectionUtil.isEmpty(ticketUserVoList)) {
            TicketUserListDto ticketUserListDto = new TicketUserListDto();
            ticketUserListDto.setUserId(programOrderCreateDto.getUserId());
            ApiResponse<List<TicketUserVo>> apiResponse = userClient.list(ticketUserListDto);
            if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                ticketUserVoList = apiResponse.getData();
            }else {
                log.error("user client rpc getUserAndTicketUserList select response : {}", JSON.toJSONString(apiResponse));
                throw new TicketFlowFrameException(apiResponse);
            }
        }
        if (CollectionUtil.isEmpty(ticketUserVoList)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        Map<Long, TicketUserVo> ticketUserVoMap = ticketUserVoList.stream()
                .collect(Collectors.toMap(TicketUserVo::getId, ticketUserVo -> ticketUserVo, (v1, v2) -> v2));
        for (Long ticketUserId : programOrderCreateDto.getTicketUserIdList()) {
            if (Objects.isNull(ticketUserVoMap.get(ticketUserId))) {
                throw new TicketFlowFrameException(BaseCode.TICKET_USER_EMPTY);
            }
        }
        ProgramGetDto programGetDto = new ProgramGetDto();
        programGetDto.setId(programOrderCreateDto.getProgramId());
        ProgramVo programVo = programService.detailV2(programGetDto);
        if (Objects.isNull(programVo)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        // 以下注释掉的代码为之前的单人限购逻辑（ACCOUNT_ORDER_COUNT）：
        // 通过 redis/Feign 查询用户已购数量，与 perAccountLimitPurchaseCount 比较。
        // 当前版本移除该限制，保留注释供后续参考恢复。
//        Integer count = 0;
//        if (redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
//                programOrderCreateDto.getUserId(),programOrderCreateDto.getProgramId()))) {
//            count = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
//                    programOrderCreateDto.getUserId(),programOrderCreateDto.getProgramId()), Integer.class);
//        }else {
//            AccountOrderCountDto accountOrderCountDto = new AccountOrderCountDto();
//            accountOrderCountDto.setUserId(programOrderCreateDto.getUserId());
//            accountOrderCountDto.setProgramId(programOrderCreateDto.getProgramId());
//            ApiResponse<AccountOrderCountVo> apiResponse = orderClient.accountOrderCount(accountOrderCountDto);
//            if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
//                count = Optional.ofNullable(apiResponse.getData()).map(AccountOrderCountVo::getCount).orElse(0);
//                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
//                                programOrderCreateDto.getUserId(),
//                                programOrderCreateDto.getProgramId()),
//                        count, tokenExpireManager.getTokenExpireTime() + 1, TimeUnit.MINUTES);
//            }
//        }

        Integer seatCount = Optional.ofNullable(programOrderCreateDto.getSeatDtoList()).map(List::size).orElse(0);

        Integer ticketCount = Optional.ofNullable(programOrderCreateDto.getTicketCount()).orElse(0);
//        if (seatCount != 0) {
//            count = count + seatCount;
//        }else if (ticketCount != 0) {
//            count = count + ticketCount;
//        }
//        if (count > programVo.getPerAccountLimitPurchaseCount()) {
//            throw new TicketFlowFrameException(BaseCode.PER_ACCOUNT_PURCHASE_COUNT_OVER_LIMIT);
//        }
    }
    
    /**
     * 父节点顺序（挂载在 order=1 的父节点下）。
     *
     * @return 1
     */
    @Override
    public Integer executeParentOrder() {
        return 1;
    }

    /**
     * 执行层级（第 2 层，参数校验之后执行）。
     *
     * @return 2
     */
    @Override
    public Integer executeTier() {
        return 2;
    }

    /**
     * 同层级中的执行顺序（第 2 个）。
     *
     * @return 2
     */
    @Override
    public Integer executeOrder() {
        return 2;
    }
}
