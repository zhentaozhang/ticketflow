package com.ticketflow.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.mapper.RefundBillMapper;
import org.springframework.stereotype.Service;

/**
 * 退款账单服务——RefundBill 表的 MyBatis-Plus Service。
 *
 * 提供退款记录的持久化操作
 */
@Service
public class RefundBillService extends ServiceImpl<RefundBillMapper, RefundBill> {

}
