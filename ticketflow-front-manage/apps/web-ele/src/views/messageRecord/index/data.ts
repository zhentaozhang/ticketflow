import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import { $t } from '#/locales';

export interface TableRow {
  messageProducerRecordId: string;
  messageConsumerRecordId: string;
  messageTypeName: string;
  messageTraceId: string;
  messageBusinessesId: string;
  messageId: string;
  messageTopic: string;
  messageContent: string;
  messageSendException: string;
  messageConsumerException: string;
  messageSendStatusName: string;
  messageConsumerStatusName: string;
  messageConsumerCount: string;
  reconciliationStatusName: string;
  reconciliationStatus: string;
  sendTime: string;
  consumerTime: string;
}

export function useColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'messageProducerRecordId', title: '消息的发送记录id', minWidth: 170, slots: { default: 'orderNumberCell' } },
    { field: 'messageConsumerRecordId', title: '消息的消费记录id', minWidth: 170 },
    { field: 'messageTypeName', title: '消息的类型', minWidth: 110 },
    { field: 'messageTraceId', title: '消息的链路id', minWidth: 170 },
    { field: 'messageBusinessesId', title: '消息的业务id', minWidth: 170 },
    { field: 'messageId', title: '消息id', minWidth: 170 },
    { field: 'messageTopic', title: '消息主题', minWidth: 150 },
    { field: 'messageContent', title: '消息内容', minWidth: 170 },
    { field: 'messageSendStatusName', title: '消息发送状态', minWidth: 100 },
    { field: 'messageSendException', title: '消息发送失败异常信息', minWidth: 150 },
    { field: 'messageConsumerStatusName', title: '消息消费状态', minWidth: 100 },
    { field: 'messageConsumerException', title: '消息消费失败异常信息', minWidth: 150 },
    { field: 'messageConsumerCount', title: '消息的消费次数', minWidth: 70 },
    { field: 'reconciliationStatusName', title: '消息对账状态', minWidth: 120, slots: { default: 'reconciliationStatusCell' } },
    { field: 'sendTime', title: '消息发送时间', minWidth: 160 },
    { field: 'consumerTime', title: '消息消费时间', minWidth: 160 },
    {
      align: 'center',
      field: 'operation',
      fixed: 'right',
      title: $t('ticketflow.index.operation'),
      width: 130,
      slots: { default: 'operationCell' }
    },
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
