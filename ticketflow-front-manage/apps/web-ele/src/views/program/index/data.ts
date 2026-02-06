import type { OnActionClickFn,VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ProgramApi } from '#/api/program';
import { $t } from '#/locales';
export function useColumns<T = ProgramApi.ProgramListResult>(
  onActionClick: OnActionClickFn<T>,
): VxeTableGridOptions['columns'] {
  return [
    {
      field: 'id',
      title: '节目id',
      minWidth: 50,
    },
    {
      field: 'title',
      title: '节目标题',
      minWidth: 150,
    },
    {
      field: 'actor',
      title: '演出明星',
      minWidth: 100,
    },
    {
      field: 'itemPicture',
      title: '简介图片',
      width: 75,
      minWidth: 75,
      maxWidth: 75,
      slots: {
        default: 'itemPicture',
      },
    },
    {
      field: 'areaName',
      title: '演出地区',
      minWidth: 100,
    },
    {
      field: 'programCategoryName',
      title: '节目类型',
      minWidth: 100,
    },
    {
      field: 'showTime',
      title: '演出时间',
      minWidth: 100,
    },
    {
      field: 'minPrice',
      title: '最低票价',
      minWidth: 100,
    },
    {
      field: 'maxPrice',
      title: '最高票价',
      minWidth: 100,
    },
    {
      // 【操作列配置】最右侧的操作按钮列
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'namespace',
          nameTitle: $t('dockDataCenter.index.namespace'),
          onClick: onActionClick, // 绑定按钮点击事件处理函数
        },
        options: [
          {
            code: 'viewTicket',
            text: true,
            btnName: '查看余票详情',
          },
          {
            code: 'viewRecord',
            text: true,
            btnName: '查看记录详情',
          },
          {
            code: 'invalidProgram',
            text: true,
            btnName: '下架节目',
          },
        ],
        name: 'CellOperation', // 使用自定义的CellOperation渲染器
      },
      field: 'operation',
      fixed: 'right',         // 固定在表格右侧，方便用户操作
      title: $t('ticketflow.index.operation'),
      width: 260,
    },
  ];
}

export function useSchema() {
  return [
    {
      component: 'Input',
      fieldName: 'ruleDescribe',
      label: '规则描述',
    },
    {
      component: 'Input',
      fieldName: 'dateTime',
      label: '时间',
    },
  ];
}
