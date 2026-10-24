package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.entity.ChannelTableData;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.Status;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ChannelDataMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.ChannelDataPublicVo;
import com.ticketflow.vo.GetChannelDataVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 渠道数据服务——管理客户端渠道（App/Web/小程序等）的注册与查询。
 * <p>
 * 密钥治理约定（修复 P0-5）：
 * <ul>
 *   <li>{@link #getByCode} 是公开接口，只返回 {@link ChannelDataPublicVo}（公钥级别，无任何私钥）；</li>
 *   <li>{@link #getSecretByCode} 是内部接口（Controller 上标 @InternalApi，需要内部令牌），才返回含私钥的 {@link GetChannelDataVo}；</li>
 *   <li>{@link #add} 只登记渠道，落库状态为"未启用"，不允许直接参与验签；</li>
 *   <li>{@link #approve} 才是"人工审核通过"那一步：把状态置为启用并清掉网关侧渠道缓存。</li>
 * </ul>
 * status 同时是 MyBatis-Plus 的逻辑删除标记（0=删除/禁用，1=正常），因此未审核的渠道
 * 对所有查询链路天然不可见——攻击者即使绕过 /add 鉴权插入数据，也拿不到一个可用的信任锚。
 */
@Service
@Slf4j
public class ChannelDataService {

    @Autowired
    private ChannelDataMapper channelDataMapper;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ApiDataClient apiDataClient;

    /**
     * 公开查询：返回不含任何私钥的渠道公开数据。
     */
    public ChannelDataPublicVo getByCode(GetChannelDataByCodeDto dto) {
        ChannelDataPublicVo channelDataPublicVo = new ChannelDataPublicVo();
        ChannelTableData channelData = selectRunningChannel(dto.getCode());
        if (Objects.nonNull(channelData)) {
            BeanUtils.copyProperties(channelData, channelDataPublicVo);
        }
        return channelDataPublicVo;
    }

    /**
     * 内部查询：返回含 RSA/AES 私钥与 tokenSecret 的完整渠道配置。
     * 只允许服务端进程调用（Controller 上 @InternalApi 保护）。
     */
    public GetChannelDataVo getSecretByCode(GetChannelDataByCodeDto dto) {
        ChannelTableData channelData = selectRunningChannel(dto.getCode());
        if (Objects.isNull(channelData)) {
            throw new TicketFlowFrameException(BaseCode.CHANNEL_DATA_NOT_EXIST);
        }
        GetChannelDataVo getChannelDataVo = new GetChannelDataVo();
        BeanUtils.copyProperties(channelData, getChannelDataVo);
        return getChannelDataVo;
    }

    /**
     * 登记渠道（待审核）。落库状态为禁用（status=0，同时是逻辑删除标记），
     * 因此不会立刻参与网关验签；必须再调用 {@link #approve} 才生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void add(ChannelDataAddDto channelDataAddDto) {
        ChannelTableData channelData = new ChannelTableData();
        BeanUtils.copyProperties(channelDataAddDto, channelData);
        channelData.setId(uidGenerator.getUid());
        channelData.setCreateTime(DateUtils.now());
        channelData.setStatus(Status.STOP.getCode());
        channelDataMapper.insert(channelData);
        log.info("渠道已登记待审核 code={} id={}，审核通过前不参与验签", channelData.getCode(), channelData.getId());
    }

    /**
     * 审核通过：把待审核渠道置为启用，并删除网关侧缓存的旧渠道配置。
     * <p>
     * status 是逻辑删除字段，MyBatis-Plus 会给 update 自动追加 status=1 条件，
     * 0→1 这种更新会被过滤掉，所以这里走 mapper 里的原生 SQL。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(GetChannelDataByCodeDto dto) {
        int updated = channelDataMapper.updateStatusByCode(dto.getCode(), Status.RUN.getCode(), DateUtils.now());
        if (updated == 0) {
            throw new TicketFlowFrameException(BaseCode.CHANNEL_DATA_NOT_EXIST);
        }
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CHANNEL_DATA, dto.getCode()));
        log.info("渠道审核通过 code={}，已清理渠道缓存，密钥配置在下次请求时重新下发", dto.getCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void test(final ChannelDataAddDto channelDataAddDto) {
        add(channelDataAddDto);
        AddApiDataDto apiDataDto = new AddApiDataDto();
        apiDataDto.setHeadVersion("1.0");
        apiDataClient.add(apiDataDto);
        if ("2".equals(channelDataAddDto.getCode())) {
            throw new RuntimeException("测试异常");
        }
    }

    /**
     * 查询"启用状态"的渠道。status 既是业务状态也是逻辑删除标记，
     * 所以未审核（0）的渠道在这里查不到。
     */
    private ChannelTableData selectRunningChannel(String code) {
        LambdaQueryWrapper<ChannelTableData> wrapper = Wrappers.lambdaQuery(ChannelTableData.class)
                .eq(ChannelTableData::getStatus, Status.RUN.getCode())
                .eq(ChannelTableData::getCode, code);
        return channelDataMapper.selectOne(wrapper);
    }
}
