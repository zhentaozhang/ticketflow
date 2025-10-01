package com.ticketflow.enums;

/**
 * Job执行方式。SYNC_RUN(同步执行)/ASYNC_RUN(异步执行)，定义后台任务的运行模式。
 */
public enum JobRunType {
    /**
     * 同步执行
     * */
    SYNC_RUN,
    
    /**
     * 异步执行
     * */
    ASYNC_RUN;
    
    JobRunType() {
       
    }
    
}
