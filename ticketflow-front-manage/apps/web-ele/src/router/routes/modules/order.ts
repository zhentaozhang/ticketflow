import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:layout-template',
      order: 3,
      title: '订单管理',
    },
    name: 'orderData',
    path: '/orderData',
    children: [
      {
        path: '/orderDataQuery',
        name: 'orderDataQuery',
        meta: {
          title: '订单列表',
        },
        component: () => import('#/views/order/index/list.vue'),
      },
      {
        path: '/discardOrderDataQuery',
        name: 'discardOrderDataQuery',
        meta: {
          title: '废弃订单列表',
        },
        component: () => import('#/views/discardOrder/index/list.vue'),
      },
    ],
  },
];

export default routes;
