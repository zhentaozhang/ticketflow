package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 地区。行政区划数据（省/市/县），支持父子层级。
 * 用于节目地点、用户收货地址等场景的区域级联选择。
 * 数据表: d_area
 */
@Data
@TableName("d_area")
public class Area extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 区域id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 父区域id
     */
    private Long parentId;
    /**
     * 区域名字
     */
    private String name;

    /**
     * 1:省 2:区 3:县
     */
    private Integer type;

    /**
     * 1:是 0:否
     */
    private Integer municipality;
}
