package com.ticketflow.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.dto.UserLoginDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.OrderService;
import com.ticketflow.simulation.module.CreateProgramOrderResultModule;
import com.ticketflow.simulation.module.ProgramDetailResultModule;
import com.ticketflow.simulation.module.TickerUserListResultModule;
import com.ticketflow.simulation.module.UserLoginResultModule;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.TicketUserVo;
import com.ticketflow.vo.UserLoginVo;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.ticketflow.simulation.constant.SimulationOrderConstant.CREATE_PROGRAM_ORDER_URL;
import static com.ticketflow.simulation.constant.SimulationOrderConstant.PROGRAM_DETAIL_URL;
import static com.ticketflow.simulation.constant.SimulationOrderConstant.TICKET_USER_LIST_URL;
import static com.ticketflow.simulation.constant.SimulationOrderConstant.USER_LOGIN_URL;

/**
 * 每晚 23:30 触发的演示/仿真数据重置任务。
 * 1. 真实删除所有订单记录（DB）
 * 2. 调用仿真接口模拟创建一批新订单
 * 3. 清理废弃订单 Redis 队列并生成新数据
 * 4. 将废弃订单写入 Redis discard_order 队列供页面展示
 *
 * 专为演示环境设计，非生产功能
 */
@Slf4j
@Component
public class PresentationOrderDataTask {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private RedisCache redisCache;
    
    @Scheduled(cron = "0 30 23 * * ?")
    public void executeTask(){
        BusinessThreadPool.execute( () -> {
            try {
                log.info("订单服务定时任务重置执行");
                //真实删除所有的订单和购票人订单，购票人订单记录(普通版本没有这步)
                orderService.delOrderAndOrderTicketUser();
                //模拟创建订单
                simulationCreateOrder();
                //将原有的模拟废弃订单数据删除
                redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, 34));
                //模拟废弃订单数据，放入Redis中
                redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, 34),
                        simulationDiscardOrder());
            }catch (Exception e) {
                log.error("executeTask error",e);
            }
        });
    }
    
    /**
     * 模拟废弃订单数据
     */
    private DiscardOrder simulationDiscardOrder(){
        //模拟废弃订单数据
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        orderCreateMq.setCreateOrderTime(DateUtils.now());
        orderCreateMq.setIdentifierId(1421864797540605952L);
        orderCreateMq.setOrderNumber(1965791442215448582L);
        orderCreateMq.setOrderPrice(new BigDecimal(2000));
        orderCreateMq.setOrderVersion(4);
        orderCreateMq.setProgramId(34L);
        orderCreateMq.setProgramItemPicture("https://picsum.photos/seed/yanrenzhong/800/400");
        orderCreateMq.setProgramPermitChooseSeat(0);
        orderCreateMq.setProgramPlace("华熙LIVE");
        orderCreateMq.setProgramShowTime(DateUtils.addWeek(DateUtils.addHour(DateUtils.now(), -4), 1));
        orderCreateMq.setProgramTitle("颜人中「MOMENTⁿ」演唱会-北京站");
        orderCreateMq.setUserId(1421653760027484162L);
        
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = new ArrayList<>();
        OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
        orderTicketUserCreateDto.setCreateOrderTime(DateUtils.now());
        orderTicketUserCreateDto.setOrderNumber(1965791442215448582L);
        orderTicketUserCreateDto.setOrderPrice(new BigDecimal(2000));
        orderTicketUserCreateDto.setProgramId(34L);
        orderTicketUserCreateDto.setSeatId(10251L);
        orderTicketUserCreateDto.setSeatInfo("551排1列");
        orderTicketUserCreateDto.setTicketCategoryId(46L);
        orderTicketUserCreateDto.setTicketUserId(1421653760027500032L);
        orderTicketUserCreateDto.setUserId(1421653760027484162L);
        orderTicketUserCreateDtoList.add(orderTicketUserCreateDto);
        orderCreateMq.setOrderTicketUserCreateDtoList(orderTicketUserCreateDtoList);
        
        return new DiscardOrder(orderCreateMq, DiscardOrderReason.CONSUMER_DELAY.getCode());
    }
    
    
    public void simulationCreateOrder(){
        try {
            //先登录
            UserLoginDto userLoginDto = new UserLoginDto();
            userLoginDto.setCode("0001");
            userLoginDto.setMobile("13154982525");
            userLoginDto.setPassword("ticketflow888");
            UserLoginVo userLoginVo = userLoginHttp(userLoginDto);
            if (Objects.isNull(userLoginVo)) {
                log.error("模拟用户登录失败 userLoginDto:{}",JSON.toJSONString(userLoginDto));
                return;
            }
            //获取购票人列表
            TicketUserListDto ticketUserListDto = new TicketUserListDto();
            ticketUserListDto.setUserId(userLoginVo.getUserId());
            List<TicketUserVo> ticketUserVoList = tickerUserListHttp(ticketUserListDto);
            if (CollectionUtil.isEmpty(ticketUserVoList)){
                log.error("模拟获取购票人列表失败 ticketUserListDto:{}",JSON.toJSONString(ticketUserListDto));
                return;
            }
            //获取节目详情
            ProgramGetDto programGetDto = new ProgramGetDto();
            //这里固定使用 颜人中「MOMENTⁿ」演唱会-北京站 这个节目ID
            programGetDto.setId(34L);
            ProgramVo programVo = programDetailHttp(programGetDto);
            if (Objects.isNull(programVo)) {
                log.error("模拟获取节目详情失败 programGetDto:{}",JSON.toJSONString(programGetDto));
                return;
            }
            //获取第一个票档id
            ProgramOrderCreateDto programOrderCreateDto = getProgramOrderCreateDto(programVo, userLoginVo,
                    ticketUserVoList);
            String orderNumber = createProgramOrder(programOrderCreateDto);
            if (StringUtil.isEmpty(orderNumber)) {
                log.error("模拟创建订单失败 programOrderCreateDto:{}",JSON.toJSONString(programOrderCreateDto));
            }else {
                log.info("模拟创建订单成功 orderNumber:{}",orderNumber);
            }
        }catch (Exception e) {
            log.error("simulationCreateOrder error",e);   
        }
    }
    
    @NotNull
    private static ProgramOrderCreateDto getProgramOrderCreateDto(ProgramVo programVo, UserLoginVo userLoginVo, 
                                                                  List<TicketUserVo> ticketUserVoList) {
        Long ticketCategoryId = programVo.getTicketCategoryVoList().get(0).getId();
        //创建订单
        ProgramOrderCreateDto programOrderCreateDto = new ProgramOrderCreateDto();
        programOrderCreateDto.setUserId(userLoginVo.getUserId());
        programOrderCreateDto.setProgramId(programVo.getId());
        List<Long> ticketUserIdList = new ArrayList<>();
        ticketUserIdList.add(ticketUserVoList.get(0).getId());
        programOrderCreateDto.setTicketUserIdList(ticketUserIdList);
        programOrderCreateDto.setTicketCategoryId(ticketCategoryId);
        programOrderCreateDto.setTicketCount(1);
        return programOrderCreateDto;
    }
    
    public UserLoginVo userLoginHttp(UserLoginDto userLoginDto){
        String result = HttpRequest.post(USER_LOGIN_URL)
                .timeout(20000)
                .body(JSON.toJSONString(userLoginDto))
                .execute().body();
        UserLoginResultModule userLoginResultModule = JSON.parseObject(result, UserLoginResultModule.class);
        if (!Objects.equals(userLoginResultModule.getCode(), BaseCode.SUCCESS.getCode())) {
            return null;
        }
        return userLoginResultModule.getData();
    }
    
    public List<TicketUserVo> tickerUserListHttp(TicketUserListDto ticketUserListDto){
        String result = HttpRequest.post(TICKET_USER_LIST_URL)
                .timeout(20000)
                .body(JSON.toJSONString(ticketUserListDto))
                .execute().body();
        TickerUserListResultModule tickerUserListResultModule = JSON.parseObject(result, TickerUserListResultModule.class);
        if (!Objects.equals(tickerUserListResultModule.getCode(), BaseCode.SUCCESS.getCode())) {
            return null;
        }
        return tickerUserListResultModule.getData();
    }
    
    public ProgramVo programDetailHttp(ProgramGetDto programGetDto){
        String result = HttpRequest.post(PROGRAM_DETAIL_URL)
                .timeout(20000)
                .body(JSON.toJSONString(programGetDto))
                .execute().body();
        ProgramDetailResultModule programDetailResultModule = JSON.parseObject(result, ProgramDetailResultModule.class);
        if (!Objects.equals(programDetailResultModule.getCode(), BaseCode.SUCCESS.getCode())) {
            return null;
        }
        return programDetailResultModule.getData();
    }
    
    public String createProgramOrder(ProgramOrderCreateDto programOrderCreateDto){
        String result = HttpRequest.post(CREATE_PROGRAM_ORDER_URL)
                .timeout(20000)
                .body(JSON.toJSONString(programOrderCreateDto))
                .execute().body();
        CreateProgramOrderResultModule createProgramOrderResultModule = JSON.parseObject(result, CreateProgramOrderResultModule.class);
        if (!Objects.equals(createProgramOrderResultModule.getCode(), BaseCode.SUCCESS.getCode())) {
            return null;
        }
        return createProgramOrderResultModule.getData();
        
    } 
}
