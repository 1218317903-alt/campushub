<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

/**
 * 应用外壳（顶栏 + 内容区 + 页脚）。
 *
 * <h2>导航项只列「已经存在」的页面</h2>
 * 提前挂上一排点不动的入口，会让人误判项目进度。因此这里只放已经上线的模块：
 * 社区、我的空间与运行状态。Discover / AI 各自 Phase 上线时再追加。
 *
 * <h2>版本号取自后端，不写死在页面上</h2>
 * 顶栏曾经硬编码过一个阶段徽标，结果是社区上线后它还停在上一阶段 ——
 * 一个只有人工记得去改的常量，迟早会变成错误信息。现在版本从
 * `/api/v1/system/info` 读取（{@code app_metadata} 表是它的真值来源），
 * 页面只是它的投影，不可能与制品漂移。
 */
const route = useRoute()
const router = useRouter()
const app = useAppStore()
const auth = useAuthStore()

interface NavItem {
  name: string
  label: string
  /** 用于比较的路径前缀；'/' 表示精确匹配首页 */
  to: string
}

const navItems: NavItem[] = [
  { name: 'community', label: '校园社区', to: '/community' },
  { name: 'my-favorites', label: '我的收藏', to: '/community/favorites' },
  { name: 'spaces', label: '我的空间', to: '/spaces' },
  { name: 'system-status', label: '运行状态', to: '/system' },
]

/**
 * 判断某个导航项是否处于选中态。
 *
 * <p>取**命中项中最长前缀**的那一个：否则 `/community/favorites` 会同时点亮
 * 「校园社区」与「我的收藏」，而用户看到两个高亮项时无法判断自己在哪。
 *
 * @param item 导航项
 * @returns 是否选中
 */
function isActive(item: NavItem): boolean {
  const path = route.path
  if (item.to === '/') {
    return path === '/'
  }
  const hits = navItems.filter((candidate) => candidate.to !== '/' && path.startsWith(candidate.to))
  if (hits.length === 0) {
    return false
  }
  const longest = hits.reduce((a, b) => (a.to.length >= b.to.length ? a : b))
  return longest.to === item.to
}

/** 未登录时不显示昵称，显示入口；已登录时优先展示昵称 */
const displayName = computed(
  () => auth.profile?.nickname || auth.profile?.username || '我的账号',
)

/** 页脚展示的版本；后端不可达时不编造一个数字，直接说明取不到 */
const versionLabel = computed(() => {
  const version = app.info?.version
  if (!version) {
    return '版本信息不可用'
  }
  return `v${version}`
})

const schemaLabel = computed(() => app.info?.schemaBaseline || '—')

/**
 * 登出。
 *
 * <p>登出之后回社区首页而不是留在原地：当前页面可能是「我的收藏」这类
 * 需要登录的页面，留在原地会立刻被路由守卫弹到登录页，看起来像报错。
 */
async function onLogout(): Promise<void> {
  await auth.logout()
  void router.push({ name: 'community' })
}

function goLogin(): void {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}

onMounted(() => {
  // store 内部有缓存，因此首页等同样读取它的页面不会产生第二次请求
  void app.loadSystemInfo()
})
</script>

<template>
  <div class="ch-shell">
    <header class="ch-header">
      <div class="ch-header__inner">
        <RouterLink class="ch-brand" to="/">
          <span class="ch-brand__mark">CH</span>
          <span class="ch-brand__text">
            CampusHub <span class="ch-brand__accent">AI</span>
          </span>
        </RouterLink>

        <nav class="ch-nav" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.name"
            class="ch-nav__link"
            :class="{ 'ch-nav__link--active': isActive(item) }"
            :to="item.to"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="ch-account">
          <template v-if="auth.isLoggedIn">
            <span class="ch-account__name" :title="displayName">{{ displayName }}</span>
            <a-button size="small" type="text" @click="onLogout">登出</a-button>
          </template>
          <template v-else>
            <a-button size="small" type="text" @click="goLogin">登录</a-button>
          </template>
        </div>
      </div>
    </header>

    <main class="ch-main">
      <RouterView />
    </main>

    <footer class="ch-footer">
      <span>CampusHub AI · {{ versionLabel }} · 数据库基线 {{ schemaLabel }}</span>
      <span class="ch-footer__hint">后端接口：GET /api/v1/system/info</span>
    </footer>
  </div>
</template>

<style scoped>
.ch-shell {
  display: flex;
  min-height: 100%;
  flex-direction: column;
}

.ch-header {
  position: sticky;
  top: 0;
  z-index: 10;
  border-bottom: 1px solid var(--ch-border);
  background: var(--ch-bg-surface);
}

.ch-header__inner {
  display: flex;
  max-width: var(--ch-content-max-width);
  margin: 0 auto;
  padding: 0 24px;
  height: 60px;
  align-items: center;
  gap: 28px;
}

.ch-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--ch-text-primary);
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
}

.ch-brand__mark {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: var(--ch-radius);
  background: var(--ch-brand);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.ch-brand__accent {
  color: var(--ch-brand);
}

.ch-nav {
  display: flex;
  flex: 1;
  gap: 4px;
}

.ch-nav__link {
  padding: 6px 12px;
  border-radius: var(--ch-radius);
  color: var(--ch-text-secondary);
  font-size: 14px;
  text-decoration: none;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.ch-nav__link:hover {
  background: var(--ch-brand-weak);
  color: var(--ch-brand);
}

.ch-nav__link--active {
  background: var(--ch-brand-weak);
  color: var(--ch-brand);
  font-weight: 500;
}

.ch-account {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
}

.ch-account__name {
  max-width: 160px;
  overflow: hidden;
  color: var(--ch-text-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ch-main {
  flex: 1;
  width: 100%;
  max-width: var(--ch-content-max-width);
  margin: 0 auto;
  padding: 28px 24px 48px;
}

.ch-footer {
  display: flex;
  max-width: var(--ch-content-max-width);
  margin: 0 auto;
  padding: 20px 24px 32px;
  width: 100%;
  justify-content: space-between;
  border-top: 1px solid var(--ch-border);
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.ch-footer__hint {
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 720px) {
  .ch-header__inner {
    gap: 12px;
    padding: 0 16px;
  }

  .ch-account__name {
    display: none;
  }

  .ch-main,
  .ch-footer {
    padding-left: 16px;
    padding-right: 16px;
  }

  .ch-footer {
    flex-direction: column;
    gap: 6px;
  }
}
</style>