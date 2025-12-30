package com.ticketflow.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import org.springframework.stereotype.Service;

/**
 * 购票人订单记录服务——OrderTicketUserRecord 表的 MyBatis-Plus Service。
 *
 * 维护订单中每个购票人的具体执行记录（座位、票档、状态等）
 */
@Service
public class OrderTicketUserRecordService extends ServiceImpl<OrderTicketUserRecordMapper, OrderTicketUserRecord> {

}
