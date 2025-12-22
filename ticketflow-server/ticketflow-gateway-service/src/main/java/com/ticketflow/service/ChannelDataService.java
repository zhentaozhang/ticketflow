package com.ticketflow.service;

import com.ticketflow.client.BaseDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.ArgumentError;
import com.ticketflow.exception.ArgumentException;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.GetChannelDataVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.ticketflow.constant.GatewayConstant.CODE;

/**
 * 渠道数据获取服务。
 * <p>
 * 作用：
 * Gateway 请求进入时，根据 code 获取对应渠道配置。
 * <p>
 * 数据来源：
 * Redis缓存 -> base-data远程服务 -> Redis缓存。
 * <p>
 * 主要提供：
 * 1. 请求签名校验公钥 signPublicKey
 * 2. 数据加密公钥 dataPublicKey
 * 3. 数据解密密钥 dataSecretKey
 * 4. Token签名密钥 tokenSecret
 * <p>
 * Gateway基于Netty Reactor模型，而Feign属于阻塞调用，
 * 所以远程调用放入独立线程池执行，并设置超时保护。
 */
@Slf4j
@Service
public class ChannelDataService {

    // code为空时的异常提示
    private final static String EXCEPTION_MESSAGE = "code参数为空";

    // Feign客户端，用于调用base-data服务获取渠道信息
    // @Lazy避免Feign Bean初始化过程中的循环依赖问题
    @Lazy
    @Autowired
    private BaseDataClient baseDataClient;

    // Redis操作封装，用于缓存渠道配置
    @Autowired
    private RedisCache redisCache;

    // Gateway专用线程池，执行阻塞式Feign调用
    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    /**
     * 校验渠道code参数。
     * <p>
     * code代表请求来源渠道，
     * 后续需要根据code查询对应渠道配置。
     */
    public void checkCode(String code) {
        if (StringUtil.isEmpty(code)) {
            ArgumentError argumentError = new ArgumentError();
            argumentError.setArgumentName(CODE);
            argumentError.setMessage(EXCEPTION_MESSAGE);
            List<ArgumentError> argumentErrorList = new ArrayList<>();
            argumentErrorList.add(argumentError);

            // 参数错误交给统一异常处理返回
            throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(), argumentErrorList);
        }
    }

    /**
     * 根据渠道code获取渠道数据。
     * <p>
     * 使用Cache Aside缓存模式：
     * 1. 查询Redis
     * 2. Redis不存在调用base-data
     * 3. 查询成功写入Redis
     */
    public GetChannelDataVo getChannelDataByCode(String code) {
        checkCode(code);

        // 优先查询Redis缓存，减少远程调用
        GetChannelDataVo channelDataVo = getChannelDataByRedis(code);

        // 缓存不存在，从base-data服务查询
        if (Objects.isNull(channelDataVo)) {
            channelDataVo = getChannelDataByClient(code);

            // 查询成功后写入Redis，供后续请求使用
            setChannelDataRedis(code, channelDataVo);
        }
        return channelDataVo;
    }

    /**
     * 从Redis查询渠道配置。
     */
    private GetChannelDataVo getChannelDataByRedis(String code) {
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.CHANNEL_DATA, code), GetChannelDataVo.class);
    }

    /**
     * 将渠道配置写入Redis。
     * <p>
     * 渠道数据变化较少，因此设置较长过期时间。
     */
    private void setChannelDataRedis(String code, GetChannelDataVo getChannelDataVo) {
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CHANNEL_DATA, code), getChannelDataVo,
                60, TimeUnit.MINUTES);
    }

    /**
     * 调用base-data服务查询渠道数据。
     * <p>
     * Feign是同步阻塞调用，
     * Gateway不能直接占用Netty EventLoop线程执行，
     * 所以提交到独立线程池。
     */
    private GetChannelDataVo getChannelDataByClient(String code) {
        GetChannelDataByCodeDto getChannelDataByCodeDto = new GetChannelDataByCodeDto();
        getChannelDataByCodeDto.setCode(code);

        // 在线程池执行Feign调用，避免阻塞Gateway线程
        Future<ApiResponse<GetChannelDataVo>> future =
                threadPoolExecutor.submit(() -> baseDataClient.getByCode(getChannelDataByCodeDto));

        try {
            // 最大等待10秒，防止下游服务异常导致Gateway长期阻塞
            ApiResponse<GetChannelDataVo> getChannelDataApiResponse = future.get(10, TimeUnit.SECONDS);

            // 判断远程调用是否成功
            if (Objects.equals(getChannelDataApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                return getChannelDataApiResponse.getData();
            }
        } catch (InterruptedException e) {
            // 当前线程被中断
            log.error("baseDataClient getByCode Interrupted", e);
            throw new TicketFlowFrameException(BaseCode.THREAD_INTERRUPTED);
        } catch (ExecutionException e) {
            // Feign执行异常，例如网络错误、序列化失败
            log.error("baseDataClient getByCode execution exception", e);
            throw new TicketFlowFrameException(BaseCode.SYSTEM_ERROR);
        } catch (TimeoutException e) {
            // 超时未返回，避免慢服务拖垮Gateway
            log.error("baseDataClient getByCode timeout exception", e);
            throw new TicketFlowFrameException(BaseCode.EXECUTE_TIME_OUT);
        }

        // 查询不到渠道数据
        throw new TicketFlowFrameException(BaseCode.CHANNEL_DATA_NOT_EXIST);
    }
}