package com.ticketflow.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.ticketflow.dto.BackManageLoginDto;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.properties.BackManageProperties;
import com.ticketflow.vo.BackManageLoginVo;
import com.ticketflow.vo.BackManageUserDetailVo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 后台管理登录服务——基于 Sa-Token 的 session 管理。
 *
 * 提供 admin 账号的登录/登出/状态查询，验证管理员密码
 */
@Slf4j
@Service
public class BackManageUserService {

    private static final String DEFAULT_USERNAME = "admin";

    private static final String DEFAULT_PASSWORD = "admin";

    @Autowired
    private BackManageProperties backManageProperties;

    @PostConstruct
    public void init() {
        if (isDefaultCredential()) {
            log.error("安全告警：manage.username/password 仍为默认值 admin/admin，后台管理登录已禁用。"
                    + "请在配置中显式设置管理凭证（生产环境建议使用强口令并通过环境变量/jasypt 注入）");
        }
    }

    private boolean isDefaultCredential() {
        return DEFAULT_USERNAME.equals(backManageProperties.getUsername())
                && DEFAULT_PASSWORD.equals(backManageProperties.getPassword());
    }

    public BackManageLoginVo login(BackManageLoginDto backManageLoginDto){
        //验证用户信息
        verifyUser(backManageLoginDto);
        //登录
        StpUtil.login(backManageLoginDto.getUsername());
        SaSession session = StpUtil.getSession();
        BackManageUserDetailVo backManageUserDetailVo = new BackManageUserDetailVo();
        backManageUserDetailVo.setUserId("1");
        backManageUserDetailVo.setHomePath("/");
        backManageUserDetailVo.setRealName("admin");
        backManageUserDetailVo.setDesc("");
        backManageUserDetailVo.setUsername(backManageLoginDto.getUsername());
        backManageUserDetailVo.setAvatar("");
        session.set("userDetail", JSON.toJSONString(backManageUserDetailVo));
        BackManageLoginVo backManageLoginVo = new BackManageLoginVo();
        backManageLoginVo.setId("1");
        backManageLoginVo.setRealName("admin");
        backManageLoginVo.setUsername(backManageLoginDto.getUsername());
        backManageLoginVo.setPassword(backManageLoginDto.getPassword());
        backManageLoginVo.setAccessToken(StpUtil.getTokenValue());
        return backManageLoginVo;
    }
    
    public BackManageUserDetailVo userInfo() {
        Object userDetailObj = StpUtil.getSession().get("userDetail");
        if (userDetailObj == null) {
            throw new TicketFlowFrameException("用户未登录或登录已过期");
        }
        return JSON.parseObject(String.valueOf(userDetailObj), BackManageUserDetailVo.class);
    }
    
    public void verifyUser(BackManageLoginDto backManageLoginDto){
        if (isDefaultCredential()) {
            throw new TicketFlowFrameException("后台管理默认凭证已禁用，请在配置中设置 manage.username/password");
        }
        if (!backManageProperties.getUsername().equals(backManageLoginDto.getUsername())) {
            throw new TicketFlowFrameException("用户名错误");
        }
        if (!backManageProperties.getPassword().equals(backManageLoginDto.getPassword())) {
            throw new TicketFlowFrameException("用户密码错误");
        }
    }
}
