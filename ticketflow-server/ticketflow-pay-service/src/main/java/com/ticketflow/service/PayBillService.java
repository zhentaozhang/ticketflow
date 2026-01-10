package com.ticketflow.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.entity.PayBill;
import com.ticketflow.mapper.PayBillMapper;
import org.springframework.stereotype.Service;

/**
 * 支付账单服务——PayBill 表的 MyBatis-Plus Service。
 *
 * 提供支付账单的持久化操作
 */
@Service
public class PayBillService extends ServiceImpl<PayBillMapper, PayBill> {

}
