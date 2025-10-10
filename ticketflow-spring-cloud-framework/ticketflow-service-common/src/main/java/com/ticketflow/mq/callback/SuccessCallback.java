package com.ticketflow.mq.callback;

/**
 * MQ 操作成功回调函数式接口
 */
@FunctionalInterface
public interface SuccessCallback<T> {

    /**
     * 执行逻辑
     *
     * @param result 执行成功的结果当做参数传递
     *
     */
    void onSuccess(T result);

}
