export const orderRoutes = [
    {
        path: '/order/index',
        alias: ['/order', '/order/'],
        name: 'orderIndex',
        component: () => import('@/views/order/index.vue'),
        meta: {requiresAuth: true},
    }, 
    {
        path: '/order/payMethod',
        name: 'PayMethod',
        component: () => import('@/views/order/payMethod.vue'),
        meta: {requiresAuth: true},
    }, 
    {
        path: '/order/paySuccess',
        name: 'PaySuccess',
        component: () => import('@/views/order/paySuccess.vue'),
        meta: {requiresAuth: true},
    },
    {
        path: '/order/buyTicketUser',
        name: 'BuyTicketUser',
        component: () => import('@/views/order/buyTicketUser.vue'),
        meta: {requiresAuth: true},
    },
    {
        path: '/order/seatSelect',
        name: 'SeatSelect',
        component: () => import('@/views/order/seatSelect.vue'),
        meta: {requiresAuth: true},
    }
];
