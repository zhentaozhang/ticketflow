package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.dto.UserGetAndTicketUserListDto;
import com.ticketflow.vo.TicketUserVo;
import com.ticketflow.vo.UserGetAndTicketUserListVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 用户服务 Feign 客户端（声明式 HTTP 调用）。
 * 下游服务通过此接口查询用户基础信息和购票人列表
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "user-service",
        contextId = "userClient")
//                ↑ Nacos 注册名 = ticketflow-user-service  ↑ order-service 下单时查购票人用
//                显式 contextId（固定字符串）→ feign.client.config.userClient.* 超时配置与前缀解耦
public interface UserClient {

    /**
     * 查询购票人列表(通过userId)
     * 下单时选择已有购票人
     */
    @PostMapping(value = "/ticket/user/list")
    ApiResponse<List<TicketUserVo>> list(TicketUserListDto dto);

    /**
     * 合并查询：用户信息 + 购票人列表（一次RPC查完）
     */
    @PostMapping(value = "/user/get/user/ticket/list")
    ApiResponse<UserGetAndTicketUserListVo> getUserAndTicketUserList(UserGetAndTicketUserListDto dto);

}
