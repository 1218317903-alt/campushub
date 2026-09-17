<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

/**
 * 应用外壳（顶栏 + 内容区）。
 *
 * Phase 01 只放导航骨架。导航项刻意只列「已经存在」的页面：
 * 提前挂上一排点不动的入口，会让人误判项目进度。
 */
const route = useRoute()

interface NavItem {
  name: string
  label: string
  to: string
}

const navItems: NavItem[] = [{ name: 'home', label: '概览', to: '/' }]

const activeName = computed(() => (typeof route.name === 'string' ? route.name : ''))
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
            :class="{ 'ch-nav__link--active': activeName === item.name }"
            :to="item.to"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <a-tag class="ch-header__badge" color="arcoblue" size="small">v0.1.0 · Phase 01</a-tag>
      </div>
    </header>

    <main class="ch-main">
      <RouterView />
    </main>

    <footer class="ch-footer">
      <span>CampusHub AI · 工程基础阶段</span>
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

.ch-header__badge {
  flex-shrink: 0;
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
    gap: 16px;
    padding: 0 16px;
  }

  .ch-header__badge {
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
