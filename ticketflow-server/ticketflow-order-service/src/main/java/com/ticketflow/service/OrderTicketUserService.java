package com.ticketflow.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.mapper.OrderTicketUserMapper;
import org.springframework.stereotype.Service;

/**
 * 购票人订单关联服务——OrderTicketUser 表的 MyBatis-Plus Service。
 *
 * 维护订单与购票人的关联关系
 */
@Service
public class OrderTicketUserService extends ServiceImpl<OrderTicketUserMapper, OrderTicketUser> {

}
