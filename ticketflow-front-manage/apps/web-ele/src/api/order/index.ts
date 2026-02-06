import { requestClient } from '#/api/request';

export namespace OrderMangeApi {
  /** 操作记录数据返回值 */
  export interface RecordPageQueryResult {
    /** 节目id */
    programId: string;
    /** 订单编号 */
    orderNumber: string;
    /** 用户id */
    userId: string;
    /** 对账状态 */
    reconciliationStatus?: string;
    /** 对账状态信息 */
    reconciliationStatusName?: string;
    /** 对账状态 */
    recordOrderTickerUserManageVoList: RecordOrderTickerUserManageResult[];
  }

  export interface RecordOrderTickerUserManageResult {
    /** 购票人订单id */
    ticketUserOrderId: string;
    /** 购票人id */
    ticketUserId: string;
    /** 座位id */
    seatId: string;
    /** 座位信息 */
    seatInfo: string;
    /** redis座位之前状态 */
    redisBeforeSeatStatusName: string;
    /** redis座位之后状态 */
    redisAfterSeatStatusName: string;
    /** 票档信息 */
    ticketCategoryName: string;
    /** 订单价格 */
    orderPrice: string;
    /** 数据库记录操作类型 */
    dbRecordTypeName: string;
    /** redis记录操作类型 */
    redisRecordTypeName: string;
    /** 对账状态 */
    reconciliationStatus: string;
    /** 对账状态信息 */
    reconciliationStatusName: string;
  }

  /** 操作记录数据参数 */
  export interface RecordPageQueryParams {
    programId: string;
    pageNumber: string;
    pageSize: string;
  }

  /**
   * 通用分页返回结构，与后端 PageVo 对应
   */
  export interface PageVo<T> {
    current: number;
    size: number;
    total: number;
    records: T[];
  }

  export interface TicketCategoryListResult {
    id: string;
    programId: string;
    introduce: string;
    price: string;
    totalNumber: string;
    dbRemainNumber: string;
    redisRemainNumber: string;
  }

  export interface TicketCategoryListParams {
    programId: string;
  }

  /** 订单数据参数 */
  export interface OrderPageQueryParams {
    programId: string;
    pageNumber: string;
    pageSize: string;
  }

  /** 订单分页列表数据返回值 */
  export interface OrderPageQueryResult {
    /** 订单编号 */
    orderNumber: string;
    /** 用户id */
    userId: string;
    /** 节目标题 */
    programTitle: string;
    /** 订单价格 */
    orderPrice: string;
    /** 订单状态 */
    orderStatusName: string;
    /** 订单生成时间 */
    createOrderTime: string;
    /** 订单支付时间 */
    payOrderTime: string;
    /** 订单取消时间 */
    cancelOrderTime: string;
    /** 购票人订单集合 */
    orderTicketUserManageVoList: OrderTicketUserManageVo[];
  }

  export interface OrderTicketUserManageVo {
    /** 购票人订单id */
    id: string;
    /** 购票人id */
    ticketUserId: string;
    /** 座位id */
    seatId: string;
    /** 座位信息 */
    seatInfo: string;
    /** 购票人订单价格 */
    orderPrice: string;
    /** 购票人订单状态 */
    orderStatusName: string;
    /** 购票人订单生成时间 */
    createOrderTime: string;
    /** 购票人订单订单支付时间 */
    payOrderTime: string;
    /** 购票人订单订单取消时间 */
    cancelOrderTime: string;
  }

  /** 订单分页列表数据返回值 */
  export interface DiscardOrderPageQueryResult {
    /** 订单编号 */
    orderNumber: string;
    /** 用户id */
    userId: string;
    /** 节目标题 */
    programTitle: string;
    /** 订单价格 */
    orderPrice: string;
    /** 订单生成时间 */
    createOrderTime: string;
    /** 废弃原因 */
    discardOrderReasonName: string;
    /** 购票人订单集合 */
    discardOrderTicketUserManageVo: DiscardOrderTicketUserManageVo[];
  }

  export interface DiscardOrderTicketUserManageVo {
    /** 购票人id */
    ticketUserId: string;
    /** 座位id */
    seatId: string;
    /** 座位信息 */
    seatInfo: string;
    /** 购票人订单价格 */
    orderPrice: string;
    /** 购票人订单生成时间 */
    createOrderTime: string;
  }
}


/**
 * 操作记录分页列表
 */
export async function recordPageQueryApi(data: OrderMangeApi.RecordPageQueryParams) {
  return requestClient.post<OrderMangeApi.PageVo<OrderMangeApi.RecordPageQueryResult>>('/order/order/manage/record/page', data);
}

/**
 * 订单分页列表
 */
export async function orderPageQueryApi(data: OrderMangeApi.OrderPageQueryParams) {
  return requestClient.post<OrderMangeApi.PageVo<OrderMangeApi.OrderPageQueryResult>>('/order/order/manage/order/page', data);
}

/**
 * 订单分页列表
 */
export async function discardOrderPageQueryApi(data: OrderMangeApi.OrderPageQueryParams) {
  return requestClient.post<OrderMangeApi.PageVo<OrderMangeApi.DiscardOrderPageQueryResult>>('/order/order/manage/discard/order/page', data);
}