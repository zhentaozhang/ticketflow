import type { VxeTableGridOptions } from '#/adapter/vxe-table';

export function useColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'id', title: '座位id', minWidth: 170 },
    { field: 'programId', title: '节目id', minWidth: 110 },
    { field: 'ticketCategoryId', title: '票档id', minWidth: 170 },
    { field: 'rowCode', title: '座位的排号', minWidth: 170 },
    { field: 'colCode', title: '座位的列号', minWidth: 170 },
    { field: 'price', title: '价格', minWidth: 150 },
    { field: 'dbSellStatusName', title: '数据库中的座位状态', minWidth: 170 },
    { field: 'redisSellStatusName', title: 'Redis中的座位状态', minWidth: 100 },
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
