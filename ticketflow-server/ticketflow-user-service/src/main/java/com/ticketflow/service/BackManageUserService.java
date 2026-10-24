package com.ticketflow.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.ticketflow.dto.BackManageLoginDto;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.properties.BackManageProperties;
import com.ticketflow.vo.BackManageLoginVo;
import com.ticketflow.vo.BackManageUserDetailVo;
import java.util.Locale;
import java.util.Set;
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

    /**
     * 视为默认/弱口令的密码集合。
     * 仓库默认配置、示例配置与公开资料里出现过的管理口令都算在内：只要命中其中任意一个，后台登录一律拒绝，
     * 避免「默认 admin/admin 会被拦，但默认 admin/admin123 反而放行」这种只认一个字符串的假防护。
     */
    private static final Set<String> DEFAULT_PASSWORDS =
            Set.of("admin", "admin123", "123456", "admin@123", "password", "12345678");

    @Autowired
    private BackManageProperties backManageProperties;

    @PostConstruct
    public void init() {
        if (isDefaultCredential()) {
            log.error("安全告警：manage.username/password 仍是默认或弱口令（当前 username={}），后台管理登录已禁用。"
                    + "请通过环境变量 MANAGE_USERNAME/MANAGE_PASSWORD 或 jasypt 加密配置设置强口令，默认口令清单见 DEFAULT_PASSWORDS",
                    backManageProperties.getUsername());
        }
    }

    /**
     * 判断当前配置是否落在「默认/弱口令」范围内。
     * 空密码、空白密码同样视为弱口令——不设置密码不应等价于「无密码即可登录」。
     */
    boolean isDefaultCredential() {
        String username = backManageProperties.getUsername();
        String password = backManageProperties.getPassword();
        if (username == null || password == null || password.isBlank()) {
            return true;
        }
        return DEFAULT_USERNAME.equalsIgnoreCase(username.trim())
                && DEFAULT_PASSWORDS.contains(password.trim().toLowerCase(Locale.ROOT));
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
        backManageLoginVo.setPassword(null);
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
