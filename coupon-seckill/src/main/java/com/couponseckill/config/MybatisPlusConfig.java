package com.couponseckill.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：
 * 1. 简易分表（flash_sale_order / user_coupon → _0/_1，按 ShardingContext.userId）
 * 2. 乐观锁（flash_sale_activity.version）
 * 3. 分页
 * 集成阶段：简易分表拦截器替换为 ShardingSphere（docs/01-技术设计.md §11.2）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        TableNameHandler handler = (sql, tableName) -> {
            Long userId = ShardingContext.getUserId();
            if ((tableName.equals("flash_sale_order") || tableName.equals("user_coupon")) && userId != null) {
                return ShardingContext.shardTable(tableName, userId);
            }
            return tableName;
        };
        DynamicTableNameInnerInterceptor dynamic = new DynamicTableNameInnerInterceptor();
        dynamic.setTableNameHandler(handler);
        interceptor.addInnerInterceptor(dynamic);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
