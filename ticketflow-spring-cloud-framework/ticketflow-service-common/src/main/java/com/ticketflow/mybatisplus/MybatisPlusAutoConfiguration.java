package com.ticketflow.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 全局配置，注册到 Spring 容器。
 * <p>
 * 1. MetaObjectHandler → 自动填充 createTime / editTime（见 MybatisPlusMetaObjectHandler）
 * 2. PaginationInnerInterceptor → 分页拦截器，
 * 让 BaseMapper 的 selectPage() 自动帮你拼 COUNT + LIMIT
 */
public class MybatisPlusAutoConfiguration {

    /**
     * 注册自动填充处理器。
     * 所有继承 BaseTableData 的实体，
     * insert 时自动填 createTime 和 editTime，
     * update 时自动刷新 editTime。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MybatisPlusMetaObjectHandler();
    }

    /**
     * 注册 MyBatis-Plus 分页插件。
     * <p>
     * 不加这个，mapper.selectPage(page, wrapper) 不会分页，而是查出全部数据。
     * 加了之后，它自动拦截你写的 SQL，在前面加一句 SELECT COUNT(*)，后面加 LIMIT。
     * 比如你写 "SELECT * FROM d_program WHERE ..."
     * 它帮你改成：
     * SELECT COUNT(*) FROM d_program WHERE ...   （先算总数）
     * SELECT * FROM d_program WHERE ... LIMIT 0,10  （再取当前页）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
