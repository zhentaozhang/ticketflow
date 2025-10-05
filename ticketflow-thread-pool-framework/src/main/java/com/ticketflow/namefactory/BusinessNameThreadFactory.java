package com.ticketflow.namefactory;


/**
 * 业务线程工厂。为业务线程池提供可辨识的线程名称前缀，便于日志和监控排查。
 **/
public class BusinessNameThreadFactory extends AbstractNameThreadFactory {

    /**
     * 将线程池工厂的前缀
     * 例子:task-pool--1(线程池的数量)
     */
    @Override
    public String getNamePrefix() {
        return "task-pool" + "--" + POOL_NUM.getAndIncrement();
    }
}
