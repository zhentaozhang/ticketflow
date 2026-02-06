import type { RouteRecordRaw } from 'vue-router';
import { $t } from '#/locales';
const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:layout-template',
      order: 4,
      title: 'MQ消息记录管理',
    },
    name: 'messageRecord',
    path: '/messageRecord',
    children: [
      {
        path: '/messageRecordQuery',
        name: 'messageRecordQuery',
        meta: {
          title: $t('ticketflow.index.messageRecordListTitle'),
        },
        component: () => import('#/views/messageRecord/index/list.vue'),
      },
    ],
  },
];

export default routes;
