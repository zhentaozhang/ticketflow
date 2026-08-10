package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.*;
import com.ticketflow.vo.ProgramRecordTaskVo;
import com.ticketflow.vo.TicketCategoryDetailVo;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 节目服务 Feign 客户端（声明式 HTTP 调用）。
 * order-service 通过此接口调用节目服务的座位锁定/余票扣减、
 * 票档查询、对账记录维护等内部接口
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"program-service",
        contextId = "programClient")
//                ↑ Nacos 服务名  ↑ order-service 下单时锁座/扣余量用
//                显式 contextId（固定字符串）→ feign.client.config.programClient.* 超时配置与前缀解耦
public interface ProgramClient {
    
    /**
     * 锁座 + 扣减票档余量（核心并发控制点）
     * order-service 创建订单时调用，保证座位和余量原子操作
     */
    @PostMapping("/program/interior/reduce/remain/number")
    ApiResponse<Boolean> operateSeatLockAndTicketCategoryRemainNumber(ReduceRemainNumberDto reduceRemainNumberDto);

    /**
     * 查询票档集合（价格+余量）
     */
    @PostMapping(value = "/ticket/category/select/list")
    ApiResponse<List<TicketCategoryDetailVo>> selectList(TicketCategoryListDto ticketCategoryDto);
    
    /**
     * 获取所有节目id集合（数据迁移遍历用）
     */
    @PostMapping(value = "/program/all/list")
    ApiResponse<List<Long>> allList();
    
    /**
     * 查询节目对账记录任务
     */
    @PostMapping(value = "/program/record/task/select")
    ApiResponse<List<ProgramRecordTaskVo>> select(ProgramRecordTaskListDto programRecordTaskListDto);
    
    /**
     * 修改节目对账记录任务
     */
    @PostMapping(value = "/program/record/task/update")
    ApiResponse<Integer> update(ProgramRecordTaskUpdateDto programRecordTaskUpdateDto);
    
    /**
     * 添加节目对账记录任务
     */
    @PostMapping(value = "/program/record/task/add")
    ApiResponse<Integer> add(ProgramRecordTaskAddDto orderTicketUserRecordAddDto);
    
    /**
     * 支付成功/取消订单后操作节目数据（释放座位/恢复余量）
     */
    @PostMapping(value = "/program/interior/operate/program/data")
    ApiResponse<Boolean> operateProgramData(ProgramOperateDataDto programOperateDataDto);

    /**
     * 按节目查询票档（价格+余量+状态）
     */
    @PostMapping(value = "/ticket/category/select/list/by/program")
    ApiResponse<List<TicketCategoryDetailVo>> selectListByProgram(TicketCategoryListByProgramDto ticketCategoryListByProgramDto);
}
