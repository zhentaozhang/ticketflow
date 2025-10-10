package com.ticketflow.mybatisplus;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.ticketflow.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 自动填充处理器。
 * <p>
 * 这个类在每次 insert/update 时自动执行，
 * 给实体里的 createTime/editTime 填入当前时间。
 * <p>
 * 比如你调用 programMapper.insert(program)，
 * 不需要手动 program.setCreateTime(new Date())，
 * 这里自动帮你填好。
 */
@Slf4j
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /**
     * insert 时执行：
     * - createTime = 当前时间
     * - editTime  = 当前时间（新建时和创建时间一样）
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", DateUtils::now, Date.class);
        this.strictInsertFill(metaObject, "editTime", DateUtils::now, Date.class);
    }

    /**
     * update 时执行：
     * - editTime = 当前时间（自动记录"什么时候改的"）
     * - createTime 不变（创建时间不应该被改）
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "editTime", DateUtils::now, Date.class);
    }
}