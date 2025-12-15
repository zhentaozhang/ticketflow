package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.BackManageLoginDto;
import com.ticketflow.service.BackManageUserService;
import com.ticketflow.vo.BackManageLoginVo;
import com.ticketflow.vo.BackManageUserDetailVo;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台管理 API——管理员登录/登出/状态查询
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "manage", description = "后台")
public class ManageController {
    
    @Autowired
    private BackManageUserService backManageUserService;
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<BackManageLoginVo> login(@Valid @RequestBody BackManageLoginDto backManageLoginDto) {
        return ApiResponse.ok(backManageUserService.login(backManageLoginDto));
    }
    
    /**
     * 查询用户信息
     */
    @GetMapping("/user/info")
    public ApiResponse<BackManageUserDetailVo> getUser() {
        return ApiResponse.ok(backManageUserService.userInfo());
    }
}
