import { requestClient } from '#/api/request';

export namespace CustomizeDataApi {

  export interface MessageRecordVo {

    /**
     * 消息的发送记录id
     */
    messageProducerRecordId:string;


    /**
     * 消息的消费记录id
     */
    messageConsumerRecordId:string;

    /**
     * 消息的类型
     */
    messageTypeName:string;

    /**
     * 消息的链路id
     */
    messageTraceId:string;

    /**
     * 消息的业务id
     */
    messageBusinessesId:string;

    /**
     * 消息id
     */
    messageId:string

    /**
     * 消息主题
     */
    messageTopic:string

    /**
     * 消息内容
     */
    messageContent:string

    /**
     * 消息发送失败的异常信息
     */
    messageSendException:string;

    /**
     * 消息消费失败的异常信息
     */
    messageConsumerException:string;

    /**
     * 消息发送状态
     */
    messageSendStatusName:string;

    /**
     * 消息消费状态
     */
    messageConsumerStatusName:string;

    /**
     * 消息的消费次数
     */
    messageConsumerCount:string;

    /**
     * 消息对账状态
     */
    reconciliationStatusName:string;

    /**
     * 消息发送时间
     */
    sendTime:string;
    /**
     * 消息消费时间
     */
    consumerTime:string;
  }


  export interface MessageRecordPageParams {
    messageBusinessesId: string;
    pageNum: number;
    pageSize: number;
  }

  /**
   * 通用分页返回结构
   */
  export interface PageVo<T> {
    current: number;
    size: number;
    total: number;
    records: T[];
  }

  /** 处理异常消息参数 */
  export interface ExecuteExceptionMessageParams {
    messageId: string;
  }

  /** 处理异常消息返回 */
  export interface ExecuteExceptionMessageVo {
    /** 处理结果，true表示成功 */
    success: boolean;
  }
}
/**
 * 查询消息记录列表（后端分页）
 */
export async function messageRecordPageApi(params: CustomizeDataApi.MessageRecordPageParams) {
  return requestClient.post<CustomizeDataApi.PageVo<CustomizeDataApi.MessageRecordVo>>(
    '/customize/message/record/page',params,);
}

/**
 * 处理异常消息
 */
export async function executeExceptionMessageApi(params: CustomizeDataApi.ExecuteExceptionMessageParams) {
  return requestClient.post<boolean>(
    '/customize/message/record/execute/exception/message',
    params,
  );
}