package com.ticketflow.mq.callback;

/**
 * MQ 操作失败回调函数式接口
 */
@FunctionalInterface
public interface FailureCallback {
    
    /**
     * 执行逻辑
     * @param ex 执行失败的异常当做参数传递
     * */
    void onFailure(Throwable ex);

}