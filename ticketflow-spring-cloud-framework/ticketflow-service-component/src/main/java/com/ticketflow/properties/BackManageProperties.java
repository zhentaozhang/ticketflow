package com.ticketflow.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 后台管理认证配置属性。
 * 包含 Sa-Token 白名单 URL 列表
 */
@Data
@ConfigurationProperties(prefix = BackManageProperties.MANAGE)
public class BackManageProperties {
    
    public static final String MANAGE = "manage";
    
    private String username = "admin";
    
    private String password = "admin";
    
    private List<String> loginExcludeApi = List.of("/auth/login");
    
    private Boolean apiPasswordCall = false;
    
    private String apiPassword;
}
