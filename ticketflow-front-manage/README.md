# TicketFlow Admin — 管理后台

[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vue.js&style=flat-square)](https://vuejs.org)
[![Vite](https://img.shields.io/badge/Vite-6-646CFF?logo=vite&style=flat-square)](https://vitejs.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?logo=typescript&style=flat-square)](https://www.typescriptlang.org)
[![Element Plus](https://img.shields.io/badge/Element_Plus-2.9-409EFF?logo=element&style=flat-square)](https://element-plus.org)
[![Turbo](https://img.shields.io/badge/Turborepo-EF4444?logo=turborepo&style=flat-square)](https://turbo.build/repo)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

> TicketFlow 管理后台，基于 [Vue Vben Admin](https://github.com/vbenjs/vue-vben-admin) v5.5 构建，以 Monorepo 方式组织，提供节目管理、订单管理、座位管理、基础数据、消息记录和系统定制等运营功能。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **框架** | Vue 3（Composition API） |
| **语言** | TypeScript |
| **构建** | Vite 6 + Turborepo |
| **包管理** | pnpm 10（Workspace Monorepo） |
| **UI** | Element Plus |
| **状态管理** | Pinia |
| **路由** | Vue Router 4（支持 Hash / History 模式） |
| **HTTP** | @vben/request（基于 Axios） |
| **国际化** | @vben/locales（vue-i18n） |
| **图表** | ECharts（通过 @vben/common-ui） |
| **CSS** | Tailwind CSS + SCSS |
| **测试** | Vitest + Playwright |
| **代码检查** | ESLint、Prettier、Stylelint、Cspell |
| **Git Hooks** | Lefthook + Commitizen |

## 快速开始

```bash
# 安装依赖
pnpm install

# 启动 Element Plus 版开发服务器
pnpm dev:ele

# 其他 UI 版本
pnpm dev:antd     # Ant Design Vue 版
pnpm dev:naive    # Naive UI 版

# 全量构建
pnpm build

# 类型检查
pnpm check:type

# 单元测试
pnpm test:unit
```

> [!IMPORTANT]
> 需要 **Node.js >= 20.10** 和 **pnpm >= 9.12**。

> [!TIP]
> 开发服务器将 `/api` 反向代理到 `http://localhost:6085/ticketflow`，目标地址在 `apps/web-ele/vite.config.mts` 中配置。

## Monorepo 架构

```
ticketflow-front-manage/
├── apps/
│   └── web-ele/                    # Element Plus 应用
│       ├── src/
│       │   ├── adapter/            # UI 组件适配器
│       │   ├── api/                # API 接口
│       │   │   ├── core/           # 认证、用户、菜单
│       │   │   ├── program/        # 节目管理
│       │   │   ├── order/          # 订单管理
│       │   │   ├── seat/           # 座位管理
│       │   │   ├── base-data/      # 基础数据
│       │   │   ├── customize/      # 定制化
│       │   │   └── messageRecord/  # 消息记录
│       │   ├── layouts/            # 布局组件
│       │   ├── router/             # 路由 + 守卫
│       │   │   ├── routes/         # 路由模块
│       │   │   ├── guard.ts        # 导航守卫（认证 + 权限）
│       │   │   └── access.ts       # 动态路由生成
│       │   ├── store/              # Pinia 认证 Store
│       │   ├── views/              # 页面视图
│       │   │   ├── _core/          # 核心页面（仪表盘等）
│       │   │   ├── program/        # 节目管理
│       │   │   ├── order/          # 订单管理
│       │   │   ├── seat/           # 座位管理
│       │   │   ├── messageRecord/  # 消息记录
│       │   │   ├── discardOrder/   # 废单管理
│       │   │   └── demos/          # 示例页面
│       │   ├── locales/            # i18n 翻译
│       │   ├── bootstrap.ts        # 应用初始化
│       │   ├── preferences.ts      # UI 偏好配置
│       │   └── main.ts             # 入口文件
│       └── vite.config.mts
├── packages/
│   ├── @core/
│   │   ├── base/                   # 核心基础工具
│   │   ├── composables/            # 通用组合式函数
│   │   ├── preferences/            # 偏好管理
│   │   └── ui-kit/                 # UI 组件库（菜单等）
│   ├── constants/                  # 共享常量
│   ├── effects/
│   │   ├── access/                 # RBAC 权限控制
│   │   ├── common-ui/              # 通用 UI 组件
│   │   ├── hooks/                  # Vue Hooks
│   │   ├── layouts/                # 布局系统（认证、基础、内嵌）
│   │   ├── plugins/                # 插件（动画等）
│   │   └── request/                # HTTP 请求层
│   ├── icons/                      # 图标预设
│   ├── locales/                    # i18n 资源
│   ├── preferences/                # 偏好定义
│   ├── stores/                     # 共享 Store（用户、权限）
│   ├── styles/                     # 全局样式
│   ├── types/                      # 共享类型定义
│   └── utils/                      # 通用工具函数
├── internal/
│   ├── lint-configs/               # 共享 Lint 配置
│   ├── node-utils/                 # Node 构建工具
│   ├── tailwind-config/            # Tailwind CSS 配置
│   ├── tsconfig/                   # 共享 TypeScript 配置
│   └── vite-config/                # 共享 Vite 配置预设
└── scripts/                        # 构建与部署脚本
```

## 核心功能

- **RBAC 权限管理** — 基于用户角色动态生成路由和菜单。登录后根据后端返回的权限标识生成可访问的路由表。
- **多 UI 支持** — 同一套架构通过适配器支持 Element Plus、Ant Design Vue、Naive UI 三套 UI 框架。
- **动态路由** — `router/access.ts` 中的 `generateAccess()` 根据用户角色路由表生成可访问菜单和路由。
- **国际化（i18n）** — 通过 `@vben/locales` 实现完整的国际化支持，翻译模块按需加载。
- **偏好系统** — 用户的 UI 偏好（主题、布局、导航模式）通过 `@vben/preferences` 持久化。
- **组件适配器** — UI 框架特有的组件通过适配器层抽象，核心逻辑与 UI 框架解耦。
- **请求层** — 统一 HTTP 客户端，内置 Token 注入、错误处理、刷新 Token 等能力。
- **多布局** — 认证布局、侧边栏/顶栏布局、iframe 内嵌布局。

## 导航守卫流程

```
请求 → 核心路由？ → 是 → 已认证？ → 是 → 放行
                    否              否 → 跳转 /login
                  → 否 → 有 Token？ → 否 → 跳转 /login
                          是
                        → 已检查权限？ → 是 → 放行
                             否
                          → 获取用户信息 → 生成路由 → 放行
```

## API 代理

开发服务器将 `/api/*` 代理到后端网关：

```ts
// apps/web-ele/vite.config.mts
proxy: {
  '/api': {
    target: 'http://localhost:6085/ticketflow',
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/api/, ''),
  },
}
```

## 常用命令

| 命令 | 说明 |
|------|------|
| `pnpm dev:ele` | 启动 Element Plus 版 |
| `pnpm build` | 全量构建 |
| `pnpm build:ele` | 构建 Element Plus 版 |
| `pnpm build:docker` | 构建 Docker 镜像 |
| `pnpm check:type` | TypeScript 类型检查 |
| `pnpm test:unit` | 运行单元测试 |
| `pnpm test:e2e` | 运行端到端测试 |
| `pnpm lint` | 代码检查 |
| `pnpm format` | 自动格式化 |
| `pnpm commit` | 规范提交（Commitizen） |
