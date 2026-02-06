import type { OnActionClickFn, VxeTableGridOptions } from '#/adapter/vxe-table';
import type { OrderMangeApi } from '#/api/order';

export interface TableRow {
  ruleDescribe: string;
  dateTime: string;
  columnNameList: string[];
  rowDataVoList: any[];
  total: number;
}

export function useColumns<T = OrderMangeApi.DiscardOrderPageQueryResult>(
  _onActionClick: OnActionClickFn<T>,
): VxeTableGridOptions['columns'] {
  return [
    { type: 'expand', width: 48, fixed: 'left', slots: { content: 'ticketExpand' } },
    { field: 'orderNumber', title: '订单编号', minWidth: 220, slots: { default: 'orderNumberCell' } },
    { field: 'userId', title: '用户ID', minWidth: 150 },
    { field: 'programTitle', title: '节目名称', minWidth: 200 },
    { field: 'orderPrice', title: '订单价格', minWidth: 120 },
    { field: 'discardOrderReasonName', title: '废弃原因', minWidth: 120 },
    { field: 'createOrderTime', title: '下单时间', minWidth: 160 },
    { field: 'actions', title: '操作', width: 120, fixed: 'right', slots: { default: 'actions' } },
  ];
}

export function useSchema() {
  return [
    {
      component: 'Input',
      fieldName: 'programTitle',
      label: '节目',
      componentProps: {
        readonly: true,
        placeholder: '请选择节目',
      },
    },
    // 隐藏字段：用于查询
    {
      component: 'Input',
      fieldName: 'programId',
      label: '',
      componentProps: {
        readonly: true,
      },
    },
  ];
}
