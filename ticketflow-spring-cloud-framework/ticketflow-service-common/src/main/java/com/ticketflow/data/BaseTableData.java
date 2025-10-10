package com.ticketflow.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.Date;

/**
 * 所有数据库表的公共字段基类。
 * <p>
 * 只要继承这个类，MyBatis Plus 在 insert 时会自动帮你填 createTime 和 editTime，
 * update 时自动刷新 editTime，你不用手动 set 这两个字段。
 * status 用作逻辑删除（delete 操作变成 update set status=0，数据还在库里）
 */
@Data
public class BaseTableData {

    /**
     * FieldFill.INSERT = 只在 insert 时自动填充
     * 由 MybatisPlusMetaObjectHandler 负责执行填充逻辑
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * FieldFill.INSERT_UPDATE = insert 和 update 时都自动填充
     * 每次改数据，editTime 自动变成当前时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date editTime;

    /**
     * 逻辑删除标志
     * 1 = 正常（没删）
     * 0 = 已删除（对用户不可见，但数据还在表里）
     */
    private Integer status;
}
