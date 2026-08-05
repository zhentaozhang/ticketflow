package com.ticketflow.base;

import com.ticketflow.threadlocal.BaseParameterHolder;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 父线程 → 子线程的上下文透传工具类。
 * preprocess：保存当前线程的 MDC + BaseParameterHolder，注入父线程上下文
 * postProcess：任务执行完还原，防止线程池复用导致上下文污染
 */
public final class BaseThreadPool {

    private BaseThreadPool() {
    }

    /**
     * 上下文快照：任务执行前保存的子线程 MDC 与 BaseParameterHolder 原始状态
     */
    private record ContextSnapshot(Map<String, String> mdcContext, Map<String, String> holdContext) {
    }

    public static Map<String, String> getContextForTask() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 获取当前线程 BaseParameterHolder 原始引用。
     * 使用 getThreadLocal().get() 而非 getParameterMap()：
     * getParameterMap() 在无上下文时会新建空 Map（非 null），
     * 会导致 preprocess/postProcess 的 null 判断失效，removeParameterMap 永不执行
     */
    public static Map<String, String> getContextForHold() {
        return BaseParameterHolder.getThreadLocal().get();
    }

    public static Runnable wrapTask(final Runnable runnable, final Map<String, String> parentMdcContext, final Map<String, String> parentHoldContext) {
        return () -> executeWithContext(() -> {
            runnable.run();
            return null;
        }, parentMdcContext, parentHoldContext);
    }

    public static <T> Callable<T> wrapTask(Callable<T> task, final Map<String, String> parentMdcContext, final Map<String, String> parentHoldContext) {
        return () -> executeWithContext(task, parentMdcContext, parentHoldContext);
    }

    private static <T> T executeWithContext(Callable<T> task, Map<String, String> parentMdcContext, Map<String, String> parentHoldContext) {
        ContextSnapshot snapshot = preprocess(parentMdcContext, parentHoldContext);
        try {
            return task.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            postProcess(snapshot);
        }
    }

    private static ContextSnapshot preprocess(final Map<String, String> parentMdcContext, final Map<String, String> parentHoldContext) {
        Map<String, String> holdContext = BaseParameterHolder.getThreadLocal().get();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        if (parentMdcContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(parentMdcContext);
        }
        if (parentHoldContext == null) {
            BaseParameterHolder.removeParameterMap();
        } else {
            BaseParameterHolder.setParameterMap(parentHoldContext);
        }
        return new ContextSnapshot(mdcContext, holdContext);
    }

    private static void postProcess(ContextSnapshot snapshot) {
        Map<String, String> mdcContext = snapshot.mdcContext();
        if (mdcContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdcContext);
        }
        Map<String, String> holdContext = snapshot.holdContext();
        if (holdContext == null) {
            BaseParameterHolder.removeParameterMap();
        } else {
            BaseParameterHolder.setParameterMap(holdContext);
        }
    }
}
