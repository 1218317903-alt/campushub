import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表。
 *
 * 当前只有 Phase 01 的骨架页面。业务路由（community / workspace / discover / ai）
 * 在各自 Phase 里按模块追加，**不要**在这里提前占位空路由 ——
 * 空路由会让「功能没做」看起来像「功能坏了」。
 *
 * 一律使用动态 import（路由级懒加载）：首屏体积不随业务增长而线性膨胀。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '概览' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = typeof title === 'string' ? `${title} · CampusHub AI` : 'CampusHub AI'
})
