package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 购票人（常用联系人）。一个用户(User)下可添加多个购票人，
 * 包含真实姓名、证件类型和证件号码，用于购票时实名登记。
 * 数据表: d_ticket_user
 */
@Data
@TableName("d_ticket_user")
public class TicketUser extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 用户真实名字
     */
    private String relName;

    /**
     * 证件类型 1:身份证 2:港澳台居民居住证 3:港澳居民来往内地通行证 4:台湾居民来往内地通行证 5:护照 6:外国人永久居住证
     */
    private Integer idType;

    /**
     * 证件号码
     */
    private String idNumber;
}
