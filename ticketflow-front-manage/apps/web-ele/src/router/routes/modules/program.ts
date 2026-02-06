import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:layout-template',
      order: 1,
      title: '节目管理',
    },
    name: 'programData',
    path: '/programData',
    children: [
      {
        path: '/programDataQuery',
        name: 'programDataQuery',
        meta: {
          title: '节目列表',
        },
        component: () => import('#/views/program/index/list.vue'),
      },
    ],
  },
];

export default routes;
