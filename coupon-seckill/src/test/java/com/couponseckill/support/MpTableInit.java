package com.couponseckill.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.entity.FlashSaleOrder;
import com.couponseckill.entity.UserCoupon;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单元测试辅助：初始化 MyBatis-Plus 的 lambda 列缓存。
 * LambdaQueryWrapper/LambdaUpdateWrapper 的 lambda 解析依赖 TableInfo，
 * 正常由 Mapper 加载时初始化；纯 Mockito 测试没有 Mapper 加载，需手动初始化。
 */
public final class MpTableInit {

    private static volatile boolean initialized = false;

    private MpTableInit() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, UserCoupon.class);
        TableInfoHelper.initTableInfo(assistant, FlashSaleOrder.class);
        TableInfoHelper.initTableInfo(assistant, FlashSaleActivity.class);
        TableInfoHelper.initTableInfo(assistant, CouponTemplate.class);
        initialized = true;
    }
}
