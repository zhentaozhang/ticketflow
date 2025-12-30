package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.domain.ReconciliationTaskData;
import com.ticketflow.dto.AccountOrderCountDto;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.dto.OrderGetDto;
import com.ticketflow.dto.OrderListDto;
import com.ticketflow.dto.OrderPayCheckDto;
import com.ticketflow.dto.OrderPayDto;
import com.ticketflow.dto.OrderSimpleListDto;
import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.properties.ApiVerify;
import com.ticketflow.scheduletask.PresentationOrderDataTask;
import com.ticketflow.scheduletask.ReconciliationTask;
import com.ticketflow.service.OrderService;
import com.ticketflow.service.OrderTaskService;
import com.ticketflow.vo.AccountOrderCountVo;
import com.ticketflow.vo.OrderGetVo;
import com.ticketflow.vo.OrderListVo;
import com.ticketflow.vo.OrderPayCheckVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单 API——订单列表/详情/创建/取消/对账
 */
@RestController
@RequestMapping("/order")
@Tag(name = "order", description = "订单")
public class OrderController {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private OrderTaskService orderTaskService;
    
    @Autowired
    private ReconciliationTask reconciliationTask;
    
    @Autowired
    private PresentationOrderDataTask orderDataTask;
    
    @Autowired
    private ApiVerify apiVerify;
    
    
    @Operation(summary  = "订单创建(不提供给前端调用，只允许内部program服务调用)")
    @PostMapping(value = "/create")
    public ApiResponse<String> create(@Valid @RequestBody OrderCreateDto orderCreateDto) {
        return ApiResponse.ok(orderService.create(orderCreateDto));
    }
    
    @Operation(summary  = "订单支付")
    @PostMapping(value = "/pay")
    public ApiResponse<String> pay(@Valid @RequestBody OrderPayDto orderPayDto) {
        return ApiResponse.ok(orderService.pay(orderPayDto));
    }
    
    @Operation(summary  = "订单支付后状态检查")
    @PostMapping(value = "/pay/check")
    public ApiResponse<OrderPayCheckVo> payCheck(@Valid @RequestBody OrderPayCheckDto orderPayCheckDto) {
        return ApiResponse.ok(orderService.payCheck(orderPayCheckDto));
    }
    
    @Operation(summary  = "支付宝支付后回调通知")
    @PostMapping(value = "/alipay/notify")
    public String alipayNotify(HttpServletRequest request) {
        return orderService.alipayNotify(request);
    }
    
    @Operation(summary  = "查看订单列表")
    @PostMapping(value = "/select/list")
    public ApiResponse<List<OrderListVo>> selectList(@Valid @RequestBody OrderListDto orderListDto) {
        return ApiResponse.ok(orderService.selectList(orderListDto));
    }
    
    @Operation(summary  = "查看订单详情")
    @PostMapping(value = "/get")
    public ApiResponse<OrderGetVo> get(@Valid @RequestBody OrderGetDto orderGetDto) {
        return ApiResponse.ok(orderService.get(orderGetDto));
    }
    
    @Operation(summary  = "账户下某个节目的订单数量(不提供给前端调用，只允许内部program服务调用)")
    @PostMapping(value = "/account/order/count")
    public ApiResponse<AccountOrderCountVo> accountOrderCount(@Valid @RequestBody AccountOrderCountDto accountOrderCountDto) {
        return ApiResponse.ok(orderService.accountOrderCount(accountOrderCountDto));
    }
    
    @Operation(summary  = "查看缓存中的订单")
    @PostMapping(value = "/get/cache")
    public ApiResponse<String> getCache(@Valid @RequestBody OrderGetDto orderGetDto) {
        return ApiResponse.ok(orderService.getCache(orderGetDto));
    }
    
    @Operation(summary  = "订单详情取消")
    @PostMapping(value = "/cancel")
    public ApiResponse<Boolean> cancel(@Valid @RequestBody OrderCancelDto orderCancelDto) {
        return ApiResponse.ok(orderService.initiateCancel(orderCancelDto));
    }

    @Operation(summary  = "对账任务执行")
    @PostMapping(value = "/reconciliation/task")
    public ApiResponse<ReconciliationTaskData> reconciliationTask(@Valid @RequestBody ProgramGetDto programGetDto) {
        apiVerify.verifyApi();
        return ApiResponse.ok(orderTaskService.reconciliationTask(programGetDto.getId()));
    }
    
    @Operation(summary  = "对账任务执行(全部)")
    @PostMapping(value = "/reconciliation/task/all")
    public ApiResponse<ReconciliationTaskData> reconciliationTaskAll() {
        apiVerify.verifyApi();
        reconciliationTask.reconciliationTask();
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "通过订单编号或者用户id查询订单列表")
    @PostMapping(value = "/simple/list")
    public ApiResponse<List<OrderListVo>> simpleList(@Valid @RequestBody OrderSimpleListDto orderSimpleListDto) {
        return ApiResponse.ok(orderService.simpleList(orderSimpleListDto));
    }
    
    @Operation(summary  = "测试")
    @PostMapping(value = "/test")
    public ApiResponse<Void> test() {
        orderDataTask.executeTask();
        return ApiResponse.ok();
    }
}
