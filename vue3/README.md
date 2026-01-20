# TicketFlow 用户前端

[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vue.js&style=flat-square)](https://vuejs.org)
[![Vite](https://img.shields.io/badge/Vite-6-646CFF?logo=vite&style=flat-square)](https://vitejs.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?logo=typescript&style=flat-square)](https://www.typescriptlang.org)
[![Element Plus](https://img.shields.io/badge/Element_Plus-2.9-409EFF?logo=element&style=flat-square)](https://element-plus.org)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

> TicketFlow 面向用户的购票前端，基于 Vue 3 开发的单页应用，提供节目浏览、选座购票、订单管理、个人中心等功能。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **框架** | Vue 3（Composition API + `<script setup>`） |
| **语言** | TypeScript 5.6 |
| **构建** | Vite 6 |
| **UI** | Element Plus 2.9 |
| **状态管理** | Pinia（持久化插件） |
| **路由** | Vue Router 4（Hash 模式） |
| **HTTP** | Axios（RSA-SHA256 签名 + 拦截器鉴权） |
| **图表** | ECharts 5 |
| **富文本** | vue-quill |
| **图片裁剪** | vue-cropper |
| **模糊搜索** | Fuse.js |
| **工具库** | @vueuse/core, crypto-js, js-cookie, jsencrypt, jsrsasign, mitt, nprogress |

## 快速开始

```bash
# 安装依赖
pnpm install

# 启动开发服务器（自动代理 API 到后端）
pnpm dev

# 生产构建
pnpm build

# 预览构建产物
pnpm preview
```

> [!NOTE]
> 环境变量（`VITE_APP_BASE_API`、`VITE_APP_URL`、`VITE_SIGN_FLAG`、`VITE_SIGN_SECRET_KEY`）在 `.env.development` / `.env.production` 中配置。参考 `.env.example` 初始化。

## 项目结构

```
src/
├── api/                 # API 接口模块
│   ├── index.ts         # 首页分类、推荐节目
│   ├── accountCenter.ts # 账户中心
│   ├── accountSettings.ts # 账户设置
│   ├── allType.ts       # 节目分类
│   ├── area.ts          # 地区数据
│   ├── buyTicketUser.ts # 购票人管理
│   ├── contentDetail.ts # 节目详情
│   ├── login.ts         # 登录
│   ├── order.ts         # 订单操作
│   ├── personInfo.ts    # 个人信息
│   ├── recommendlist.ts # 推荐列表
│   └── seatDetail.ts    # 座位选择
├── assets/
│   ├── styles/          # 全局 SCSS（CSS 变量主题系统）
│   ├── login/           # 登录页素材
│   └── svg/             # SVG 图标
├── components/
│   ├── footer/          # 全局页脚
│   ├── header/          # 全局页头
│   ├── menuSidebar/     # 侧边菜单
│   ├── Pagination/      # 通用分页组件
│   ├── program-list/    # 节目卡片列表
│   ├── tf-icon/         # 自定义图标组件
│   └── verifition/      # 验证码（滑块 + 点选）
├── router/
│   ├── index.ts         # 路由配置 + 导航守卫
│   └── routes/          # 路由模块（auth、common、order、account）
├── store/
│   └── modules/
│       └── auth.ts      # Pinia 认证状态
├── types/
│   └── api.ts           # API 类型定义
├── utils/
│   ├── auth.ts          # Token 存取
│   ├── bus.ts           # 事件总线（mitt）
│   ├── constants.ts     # 应用常量
│   ├── idType.ts        # 证件类型工具
│   ├── index.ts         # 通用工具函数
│   ├── request.ts       # Axios 实例 + RSA 签名拦截器
│   └── scroll-to.ts     # 平滑滚动
├── views/
│   ├── index.vue        # 首页
│   ├── login.vue        # 登录
│   ├── register.vue     # 注册
│   ├── 404.vue          # 404 页面
│   ├── accountSettings/ # 个人设置（认证、密码、邮箱、手机）
│   ├── allType/         # 全部分类
│   ├── contentDetail/   # 节目详情页
│   ├── help/            # 帮助中心
│   ├── order/           # 下单流程（选座、支付、成功）
│   ├── orderManagement/ # 订单管理
│   └── personInfo/      # 个人信息 + 常用购票人
├── App.vue              # 根组件（router-view + 返回顶部）
├── main.ts              # 入口（Element Plus、Pinia、路由）
└── permission.ts        # 路由导航守卫（Token 检查）
```

## 接口说明

开发环境通过 Vite dev server 代理 API 到后端，生产环境使用 `VITE_APP_BASE_API` 前缀路由到 TicketFlow 微服务网关。

**签名请求** — `VITE_SIGN_FLAG=1` 时，请求体使用 RSA-SHA256 签名，传输 `{ code, businessBody, sign }` 结构，防止篡改。

**认证流程** — Axios 拦截器自动在请求头附加 `useAuthStore().token`。后端返回 `code = 10055 | 516` 时，弹出登录过期弹窗并跳转至登录页。

## 主题系统

通过 `assets/styles/index.scss` 的 CSS 自定义属性实现主题化：

| 变量 | 用途 |
|------|------|
| `--tf-primary` | 品牌色（默认 `#e2231a`） |
| `--tf-bg` | 页面背景 |
| `--tf-surface` | 卡片/表面背景 |
| `--tf-text-primary` | 主要文字色 |
| `--tf-border` | 边框色 |
| `--tf-shadow-md` | 卡片阴影 |

## 构建优化

`vite.config.ts` 中配置了手动分包策略：

- **element-plus** — Element Plus 组件库
- **vue-vendor** — Vue、Pinia、Vue Router 核心
- **vendor** — 其余 `node_modules` 依赖
