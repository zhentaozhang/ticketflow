package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 座位售卖状态查询 dto。
 * <p>
 * 订单服务在支付/取消后把 Redis（权威）与节目服务 DB（派生）收敛到同一状态：
 * 推送节目数据（operateProgramData）的响应可能丢失（超时、连接断、HTTP 层失败），
 * 这时"没收到成功响应"并不等于"DB 没改成功"。重试前先用这个查询端点核对 DB 里的
 * 座位售卖状态，把"已生效但响应丢了"和"确实没生效"区分开，避免重复推送。
 */
@Data
@Schema(title="SeatSellStatusDto", description ="座位售卖状态查询")
public class SeatSellStatusDto {

    @Schema(name ="programId", type ="Long", description ="节目id", requiredMode = RequiredMode.REQUIRED)
    @NotNull
    private Long programId;

    @Schema(name ="seatIdList", type ="List<Long>", description ="座位id集合", requiredMode = RequiredMode.REQUIRED)
    @NotEmpty
    private List<Long> seatIdList;
}
