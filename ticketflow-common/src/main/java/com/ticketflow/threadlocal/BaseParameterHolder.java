package com.ticketflow.threadlocal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 请求上下文参数 ThreadLocal 持有者。
 * <p>
 * 使用 ThreadLocal 保存当前请求线程中的上下文参数。
 * 例如：userId、channel、grayVersion 等请求级数据。
 * <p>
 * 网关接收到请求后，可以将 Header 中的数据解析后存入 ThreadLocal。
 * 后续 Feign 调用时，可以从 ThreadLocal 获取参数，并添加到下游请求 Header 中。
 * <p>
 * 请求结束后必须调用 removeParameterMap() 清理 ThreadLocal，
 * 避免线程复用导致数据污染和内存泄漏。
 */
public class BaseParameterHolder {

    // 使用 ThreadLocal 保存当前线程对应的请求参数 Map
    private static final ThreadLocal<Map<String, String>> THREAD_LOCAL_MAP = new ThreadLocal<>();


    /**
     * 设置请求参数
     * <p>
     * 如果当前线程还没有参数 Map，则创建新的 Map。
     * 然后将参数保存到当前线程上下文中。
     */
    public static void setParameter(String name, String value) {
        Map<String, String> map = THREAD_LOCAL_MAP.get();

        // 当前线程第一次存储参数，初始化 Map
        if (map == null) {
            map = new HashMap<>(64);
        }

        // 保存参数，例如 userId、channel
        map.put(name, value);

        // 将 Map 绑定到当前线程
        THREAD_LOCAL_MAP.set(map);
    }


    /**
     * 根据参数名称获取当前线程中的参数值
     * <p>
     * 如果当前线程没有保存参数，则返回 null。
     */
    public static String getParameter(String name) {
        return Optional.ofNullable(THREAD_LOCAL_MAP.get())
                .map(map -> map.get(name))
                .orElse(null);
    }


    /**
     * 删除当前线程中的指定参数
     */
    public static void removeParameter(String name) {
        Map<String, String> map = THREAD_LOCAL_MAP.get();

        if (map != null) {
            map.remove(name);
        }
    }


    /**
     * 获取 ThreadLocal 对象
     * <p>
     * 外部可以通过该方法直接操作当前线程绑定的数据。
     */
    public static ThreadLocal<Map<String, String>> getThreadLocal() {
        return THREAD_LOCAL_MAP;
    }


    /**
     * 获取当前线程保存的全部请求参数。
     * <p>
     * 如果当前线程不存在参数，则返回一个新的 Map。
     */
    public static Map<String, String> getParameterMap() {
        Map<String, String> map = THREAD_LOCAL_MAP.get();

        if (map == null) {
            map = new HashMap<>(64);
        }

        return map;
    }


    /**
     * 将参数 Map 绑定到当前线程。
     */
    public static void setParameterMap(Map<String, String> map) {
        THREAD_LOCAL_MAP.set(map);
    }


    /**
     * 清除当前线程保存的所有请求参数。
     * <p>
     * 在线程池场景下线程会复用，如果不清理，
     * 下一个请求可能读取到上一个请求的数据。
     */
    public static void removeParameterMap() {
        THREAD_LOCAL_MAP.remove();
    }
}