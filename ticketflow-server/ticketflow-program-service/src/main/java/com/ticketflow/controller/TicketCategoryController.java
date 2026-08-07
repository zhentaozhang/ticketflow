package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.TicketCategoryAddDto;
import com.ticketflow.dto.TicketCategoryDto;
import com.ticketflow.dto.TicketCategoryListDto;
import com.ticketflow.service.TicketCategoryService;
import com.ticketflow.vo.TicketCategoryDetailVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 票档 API——节目票档列表/价格/余量查询
 */
@RestController
@RequestMapping("/ticket/category")
@Tag(name = "ticket-category", description = "票档")
public class TicketCategoryController {
    
    @Autowired
    private TicketCategoryService ticketCategoryService;
    
    
    @Operation(summary  = "添加")
    @PostMapping(value = "/add")
    public ApiResponse<Long> add(@Valid @RequestBody TicketCategoryAddDto ticketCategoryAddDto) {
        return ApiResponse.ok(ticketCategoryService.add(ticketCategoryAddDto));
    }
    
    @Operation(summary  = "查询详情")
    @PostMapping(value = "/detail")
    public ApiResponse<TicketCategoryDetailVo> detail(@Valid @RequestBody TicketCategoryDto ticketCategoryDto) {
        return ApiResponse.ok(ticketCategoryService.detail(ticketCategoryDto));
    }

    @Operation(summary  = "查询集合(programId和票档id集合查询)")
    @PostMapping(value = "/select/list")
    public ApiResponse<List<TicketCategoryDetailVo>> selectList(@Valid @RequestBody TicketCategoryListDto ticketCategoryDto) {
        return ApiResponse.ok(ticketCategoryService.selectList(ticketCategoryDto));
    }
}
