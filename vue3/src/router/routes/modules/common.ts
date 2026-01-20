export const commonRoutes = [
    {
        path: "/",
        redirect: '/index',
    },
    {
        path: '/index',
        name: 'Home',
        component: () => import('@/views/index.vue'),
    },
    {
        path: '/allType/index',
        name: 'AllType',
        component: () => import('@/views/allType/index.vue'),
    },
    {
        path: '/contentDetail/index/:id',
        name: 'detail',
        component: () => import('@/views/contentDetail/index.vue'),
    },
    {
        path: '/help',
        name: 'Help',
        component: () => import('@/views/help/index.vue'),
    }
];
