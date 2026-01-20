import { createRouter, createWebHistory } from "vue-router";
import { authRoutes } from "./routes/modules/auth";
import { commonRoutes } from "./routes/modules/common";
import { orderRoutes } from "./routes/modules/order";
import { accountRoutes } from "./routes/modules/account";
import { getToken } from "@/utils/auth";
import { ElMessage } from "element-plus";

export const constantRoutes = [
    ...authRoutes,
    ...commonRoutes,
    ...orderRoutes,
    ...accountRoutes,
    {
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('@/views/404.vue')
    }
];

const router = createRouter({
    history: createWebHistory(),
    routes: constantRoutes,
    scrollBehavior(to, from, savedPosition) {
        if (savedPosition) {
            return savedPosition;
        } else {
            return { top: 0 };
        }
    },
});

// 公开无需登录验证的白名单路由
const whiteList = ['/index', '/', '/login', '/register', '/allType/index', '/help', '/404'];

router.beforeEach((to, from, next) => {
    const hasToken = getToken();

    // 匹配包含 contentDetail 的详情页
    const isPublicDetail = to.path.startsWith('/contentDetail/index');

    if (hasToken) {
        if (to.path === '/login' || to.path === '/register') {
            next({ path: '/' });
        } else {
            next();
        }
    } else {
        if (whiteList.includes(to.path) || isPublicDetail) {
            next();
        } else {
            ElMessage.warning('请先登录后再进行操作');
            next({ path: '/login', query: { redirect: to.fullPath } });
        }
    }
});

export default router;
