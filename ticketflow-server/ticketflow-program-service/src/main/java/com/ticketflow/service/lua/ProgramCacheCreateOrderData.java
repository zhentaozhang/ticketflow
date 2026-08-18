package com.ticketflow.service.lua;

import com.ticketflow.domain.PurchaseSeat;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建订单 Lua 脚本的返回体。
 * code: 0=成功，40001~40011=各种错误（座位被占/余票不足/价格不符等），40035=重复提交
 * purchaseSeatList: 成功锁定的座位列表
 * remainMap: 扣减后各票档剩余余票（V5 Lua 返回，供本地库存闸门追踪）
 */
@Data
public class ProgramCacheCreateOrderData {

    private Integer code;
    
    private List<PurchaseSeat> purchaseSeatList;

    private Map<String, Integer> remainMap;
}
