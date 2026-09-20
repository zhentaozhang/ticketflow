package com.ticketflow.namefactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程工厂抽象基类。提供自定义线程命名、分组和守护线程设置的通用能力。
 **/
public abstract class AbstractNameThreadFactory implements ThreadFactory {

    protected static final AtomicLong POOL_NUM = new AtomicLong(1);
    private final ThreadGroup group;
    private final AtomicLong threadNum = new AtomicLong(1);
    private String namePrefix = "";


    public AbstractNameThreadFactory() {
        group = Thread.currentThread().getThreadGroup();
        namePrefix = getNamePrefix() + "--thread--";
    }

    /**
     * 子类实现获取线程池名称的前缀
     *
     * @return String
     */
    public abstract String getNamePrefix();

    /**
     * 将线程池工厂中设置线程名进行重写
     * 例子:子类重写的namePrefix--thread--2(每个线程池中线程的数量)
     */
    @Override
    public Thread newThread(Runnable r) {
        String name = namePrefix + threadNum.getAndIncrement();
        Thread t = new Thread(group, r, name, 0);
        // 异步任务线程统一设为守护线程：否则线程池未显式 shutdown 时会阻止 JVM 退出。
        // 需要优雅收尾的池应自行注册 shutdown hook（见 BusinessThreadPool）。
        t.setDaemon(true);
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        return t;
    }
}
