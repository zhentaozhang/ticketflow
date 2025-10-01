package com.ticketflow.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static com.ticketflow.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static com.ticketflow.constant.Constant.PREFIX_DISTINCTION_NAME;

/**
 * Spring 容器全局持有者。
 * <p>
 * 实现 ApplicationContextInitializer，在容器刷新时保存 ConfigurableApplicationContext 引用。
 * 供非 Spring 管理的类（如工具类、Lua 脚本辅助类）获取 Bean 和配置属性。
 * <p>
 * 关键方法：
 * getPrefixDistinctionName() — 读取 prefix.distinction.name，
 * 用于 Feign 服务名和 Kafka Topic 的命名空间隔离
 * getBean()                  — 按类型/名称获取 Bean
 */

public class SpringUtil implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static ConfigurableApplicationContext configurableApplicationContext;


    public static String getPrefixDistinctionName() {
        return configurableApplicationContext.getEnvironment().getProperty(PREFIX_DISTINCTION_NAME,
                DEFAULT_PREFIX_DISTINCTION_NAME);
    }

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        configurableApplicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> requiredType) {
        return configurableApplicationContext.getBean(requiredType);
    }

    public static <T> T getBean(String name, Class<T> requiredType) {
        return configurableApplicationContext.getBean(name, requiredType);
    }
}
