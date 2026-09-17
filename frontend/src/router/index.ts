import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 路由表。
 *
 * 当前覆盖 Phase 01 的骨架页面与 Phase 03 的社区。其余业务路由
 * （workspace / discover / ai）在各自 Phase 里按模块追加，**不要**在这里提前占位空路由 ——
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
    path: '/community',
    name: 'community',
    component: () => import('@/views/community/CommunityFeedView.vue'),
    meta: { title: '校园社区' },
  },
  {
    path: '/community/new',
    name: 'post-new',
    component: () => import('@/views/community/PostEditorView.vue'),
    meta: { title: '发布帖子', requiresAuth: true },
  },
  {
    path: '/community/favorites',
    name: 'my-favorites',
    component: () => import('@/views/community/MyFavoritesView.vue'),
    meta: { title: '我的收藏', requiresAuth: true },
  },
  {
    // 静态段 /community/new 与 /community/favorites 必须排在它前面，
    // 否则 :publicId 会把 "new" 当成帖子标识吃掉（vue-router 按定义顺序匹配）
    path: '/community/posts/:publicId',
    name: 'post-detail',
    component: () => import('@/views/community/PostDetailView.vue'),
    meta: { title: '帖子' },
  },
  {
    path: '/community/posts/:publicId/edit',
    name: 'post-edit',
    component: () => import('@/views/community/PostEditorView.vue'),
    meta: { title: '编辑帖子', requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录' },
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

/**
 * 需要登录的路由守卫。
 *
 * <p>它<b>不是</b>权限边界 —— 真正的鉴权在服务端，任何人都可以绕过这个守卫，
 * 而绕过之后什么也做不了：写接口在安全过滤链上就要求已认证。
 * 这里存在的唯一理由是体验：避免用户填完一整个表单，才在提交时被拒。
 *
 * <p>刻意<b>不</b>在这里做登录态恢复：那会让每次页面跳转都可能触发一次资料请求。
 * 恢复在应用启动时做一次（见 {@code App.vue}）。
 */
router.beforeEach((to) => {
  if (to.meta.requiresAuth !== true) {
    return true
  }
  const auth = useAuthStore()
  if (auth.isLoggedIn) {
    return true
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = typeof title === 'string' ? `${title} · CampusHub AI` : 'CampusHub AI'
})
