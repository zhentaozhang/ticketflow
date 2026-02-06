import { requestClient } from '#/api/request';

export namespace MessageRecordDataApi {

  export interface MessageRecordVo {

    id:string;


    /**
     * 消息的父级链路id
     */
    messageParentTraceId:string;


    /**
     * 消息的链路id
     */
    messageTraceId:string;

    /**
     * 消息id
     */
    messageId:string

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
    /**
     * 发送的消息里视频维度分类名称
     */
    messageSendVideoDimensionTypeName:string;
    /**
     * 发送消息的日期类型名字
     */
    messageSendDateTypeName:string;
    /**
     * 消费的消息里视频维度分类名称
     */
    messageConsumerVideoDimensionTypeName:string;
    /**
     * 消费消息的日期类型名字
     */
    messageConsumerDateTypeName:string;
  }

  /**
   * 后端分页请求参数
   * - messageTraceId 可选：传了则查询该链路的全量明细（不分页）
   * - pageNum/pageSize：分页去重后的“链路分组”
   */
  export interface QueryExceptionListParams {
    messageTraceId?: string;
    pageNum: number;
    pageSize: number;
  }

  /**
   * 通用分页返回结构，与后端 PageVo 对应
   */
  export interface PageVo<T> {
    pageNum: number;
    pageSize: number;
    total: number;
    list: T[];
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
export async function queryListApi(params: MessageRecordDataApi.QueryExceptionListParams) {
  return requestClient.post<MessageRecordDataApi.PageVo<MessageRecordDataApi.MessageRecordVo>>(
    '/message/record/query/list',
    params,
  );
}

/**
 * 查询消息异常记录列表
 */
export async function queryExceptionListApi(params: MessageRecordDataApi.QueryExceptionListParams) {
  return requestClient.post<MessageRecordDataApi.MessageRecordVo>(
    '/message/record/query/exception/list',
    params,
  );
}

/**
 * 处理异常消息
 */
export async function executeExceptionMessageApi(params: MessageRecordDataApi.ExecuteExceptionMessageParams) {
  return requestClient.post<boolean>(
    '/message/record/execute/exception/message',
    params,
  );
}
