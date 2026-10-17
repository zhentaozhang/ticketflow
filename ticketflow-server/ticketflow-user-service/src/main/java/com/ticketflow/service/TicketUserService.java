package com.ticketflow.service;


import cn.hutool.core.bean.BeanUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.TicketUserDto;
import com.ticketflow.dto.TicketUserIdDto;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.entity.TicketUser;
import com.ticketflow.entity.User;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.TicketUserMapper;
import com.ticketflow.mapper.UserMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.TicketUserVo;
import com.ticketflow.threadlocal.BaseParameterHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 购票人（常用联系人）管理服务。
 *
 * 每个用户可维护多个购票人信息，
 * 支持增删改查，购票时从常用列表选择
 */
@Service
public class TicketUserService extends ServiceImpl<TicketUserMapper, TicketUser> {
    
    @Autowired
    private TicketUserMapper ticketUserMapper;
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    public List<TicketUserVo> list(TicketUserListDto ticketUserListDto) {
        return redisCache.getValueIsList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST, ticketUserListDto.getUserId()),
                TicketUserVo.class,
                () -> {
                    LambdaQueryWrapper<TicketUser> ticketUserLambdaQueryWrapper = Wrappers.lambdaQuery(TicketUser.class)
                            .eq(TicketUser::getUserId, ticketUserListDto.getUserId());
                    List<TicketUser> ticketUsers = ticketUserMapper.selectList(ticketUserLambdaQueryWrapper);
                    return BeanUtil.copyToList(ticketUsers, TicketUserVo.class);
                },
                7,
                java.util.concurrent.TimeUnit.DAYS
        );
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void add(TicketUserDto ticketUserDto) {
        User user = userMapper.selectById(ticketUserDto.getUserId());
        if (Objects.isNull(user)) {
            throw new TicketFlowFrameException(BaseCode.USER_EMPTY);
        }
        LambdaQueryWrapper<TicketUser> ticketUserLambdaQueryWrapper = Wrappers.lambdaQuery(TicketUser.class)
                .eq(TicketUser::getUserId, ticketUserDto.getUserId())
                .eq(TicketUser::getIdType, ticketUserDto.getIdType())
                .eq(TicketUser::getIdNumber, ticketUserDto.getIdNumber());
        TicketUser ticketUser = ticketUserMapper.selectOne(ticketUserLambdaQueryWrapper);
        if (Objects.nonNull(ticketUser)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_EXIST);
        }
        TicketUser addTicketUser = new TicketUser();
        BeanUtil.copyProperties(ticketUserDto,addTicketUser);
        addTicketUser.setId(uidGenerator.getUid());
        ticketUserMapper.insert(addTicketUser);
        delTicketUserVoListCache(String.valueOf(ticketUserDto.getUserId()));
    }
    @Transactional(rollbackFor = Exception.class)
    public void delete(TicketUserIdDto ticketUserIdDto) {
        TicketUser ticketUser = ticketUserMapper.selectById(ticketUserIdDto.getId());
        if (Objects.isNull(ticketUser)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        String currentUserId = BaseParameterHolder.getParameter(com.ticketflow.constant.Constant.USER_ID);
        if (com.ticketflow.util.StringUtil.isNotEmpty(currentUserId)
                && !String.valueOf(ticketUser.getUserId()).equals(currentUserId)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_NO_AUTH);
        }
        LambdaQueryWrapper<TicketUser> deleteWrapper = Wrappers.lambdaQuery(TicketUser.class)
                .eq(TicketUser::getId, ticketUserIdDto.getId())
                .eq(TicketUser::getUserId, ticketUser.getUserId());
        ticketUserMapper.delete(deleteWrapper);
        delTicketUserVoListCache(String.valueOf(ticketUser.getUserId()));
    }
    
    public void delTicketUserVoListCache(String userId){
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST, userId));
    }
}
