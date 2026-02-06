import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:layout-template',
      order: 2,
      title: '座位管理',
    },
    name: 'seatData',
    path: '/seatData',
    children: [
      {
        path: '/seatDataQuery',
        name: 'seatDataQuery',
        meta: {
          title: '座位列表',
        },
        component: () => import('#/views/seat/index/list.vue'),
      },
    ],
  },
];

export default routes;
