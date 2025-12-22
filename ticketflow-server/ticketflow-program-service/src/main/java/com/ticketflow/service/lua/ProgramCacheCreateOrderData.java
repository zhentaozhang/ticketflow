package com.ticketflow.service.lua;

import com.ticketflow.domain.PurchaseSeat;
import lombok.Data;

import java.util.List;

/**
 * 创建订单 Lua 脚本的返回体。
 * code: 0=成功，40001~40011=各种错误（座位被占/余票不足/价格不符等）
 * purchaseSeatList: 成功锁定的座位列表
 */
@Data
public class ProgramCacheCreateOrderData {

    private Integer code;
    
    private List<PurchaseSeat> purchaseSeatList;
}
