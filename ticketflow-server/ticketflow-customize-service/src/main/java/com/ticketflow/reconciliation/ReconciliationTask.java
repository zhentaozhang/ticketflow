package com.ticketflow.reconciliation;

/**
 * 对账任务接口。函数式接口，定义对账任务的执行契约。
 */
@FunctionalInterface
public interface ReconciliationTask {
    
    /***
     * 执行任务
     */
    void run();
}
