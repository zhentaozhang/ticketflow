package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户。平台用户账户，包含姓名、手机号、邮箱、密码、实名认证等信息。
 * 关联购票人(TicketUser)、用户手机(UserMobile)、用户邮箱(UserEmail)。
 * 数据表: d_user
 */
@Data
@TableName("d_user")
public class User extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 用户名字
     */
    private String name;

    /**
     * 用户真实名字
     */
    private String relName;

    /**
     * 手机号
     *
     */
    private String mobile;

    /**
     * 1:男 2:女
     */
    private Integer gender;

    /**
     * 密码
     */
    private String password;

    /**
     * 是否邮箱认证 1:已验证 0:未验证
     */
    private Integer emailStatus;

    /**
     * 邮箱地址
     */
    private String email;

    /**
     * 是否实名认证 1:已验证 0:未验证
     */
    private Integer relAuthenticationStatus;

    /**
     * 身份证号码
     */
    private String idNumber;

    /**
     * 收货地址
     *
     */
    private String address;


}
