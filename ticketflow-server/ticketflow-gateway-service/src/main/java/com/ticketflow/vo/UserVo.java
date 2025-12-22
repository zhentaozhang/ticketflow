package com.ticketflow.vo;

import lombok.Data;

import java.util.Date;

/**
 * 用户信息视图对象。网关层返回给客户端的用户数据格式。
 */
@Data
public class UserVo {
    
    private String id;
    
    private String name;
    
    private String password;
    
    private Integer age;
    
    private Integer status;
    
    private Date createTime;
    
    private String mobile;
    
    private Date editTime;
}
