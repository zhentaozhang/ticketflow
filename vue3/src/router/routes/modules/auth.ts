export const authRoutes = [
    {
        path: '/login',
        name: 'Login',
        component: () => import('@/views/login.vue'),
        hidden: true
    },
    {
        path: '/register',
        name: 'Register',
        component: () => import('@/views/register.vue'),
        hidden: true
    }
];
