export const accountRoutes = [
    {
        path: '/personInfo/index',
        name: 'PersonInfo',
        component: () => import('@/views/personInfo/index.vue'),
        meta: {requiresAuth: true}
    },
    {
        path: '/personInfo/ticketUser',
        name: 'TicketUser',
        component: () => import('@/views/personInfo/ticketUser.vue'),
        meta: {requiresAuth: true}
    }, 
    {
        path: '/accountSettings/index',
        name: 'AccountSettings',
        component: () => import('@/views/accountSettings/index.vue'),
        meta: {requiresAuth: true}
    }, 
    {
        path: '/orderManagement/index',
        name: 'OrderManagement',
        component: () => import('@/views/orderManagement/index.vue'),
        meta: {requiresAuth: true}
    }, 
    {
        path: '/orderManagement/orderDetail/:orderNumber',
        name: 'orderDetail',
        component: () => import('@/views/orderManagement/orderDetail.vue'),
        meta: {requiresAuth: true}
    }, 
    {
        path: '/accountSettings/editPassword',
        name: 'EditPassword',
        component: () => import('@/views/accountSettings/components/editPassword.vue'),
        meta: {requiresAuth: true}
    },
    {
        path: '/accountSettings/email',
        name: 'EditEmail',
        component: () => import('@/views/accountSettings/components/email.vue'),
        meta: {requiresAuth: true}
    },
    {
        path: '/accountSettings/mobile',
        name: 'EditMobile',
        component: () => import('@/views/accountSettings/components/mobile.vue'),
        meta: {requiresAuth: true}
    },
    {
        path: '/accountSettings/authentication',
        name: 'Authentication',
        component: () => import('@/views/accountSettings/components/authentication.vue'),
        meta: {requiresAuth: true}
    }
];
