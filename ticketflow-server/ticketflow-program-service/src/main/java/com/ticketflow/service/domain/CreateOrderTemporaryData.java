package com.ticketflow.service.domain;

import com.ticketflow.domain.PurchaseSeat;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 创建订单临时数据。下单过程中用于暂存中间数据的领域对象。
 */
@Data
@AllArgsConstructor
public class CreateOrderTemporaryData {

    /**
     * 记录id
     */
    private Long identifierId;
    
    /**
     * 购买的座位
     * */
    private List<PurchaseSeat> purchaseSeatList;
   
}
