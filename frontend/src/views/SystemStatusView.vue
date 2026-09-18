<script setup lang="ts">
import { computed, onMounted } from 'vue'

import { useAppStore } from '@/stores/app'

/** 独立的运行状态页，展示真实后端与数据库信息。 */
const store = useAppStore()

onMounted(() => {
  void store.loadSystemInfo()
})

function formatServerTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

const infoRows = computed(() => {
  const info = store.info
  if (!info) {
    return []
  }
  return [
    { label: '应用名', value: info.application },
    { label: '版本', value: info.version },
    { label: '生效 Profile', value: info.profiles },
    { label: '运行时 Java', value: info.javaVersion },
    { label: '服务端时间', value: `${formatServerTime(info.serverTime)} (UTC+8)` },
    { label: '数据库 Schema 基线', value: info.schemaBaseline || '未检测到（迁移可能未生效）' },
  ]
})
</script>

<template>
  <div class="ch-page">
    <section class="ch-hero">
      <h1 class="ch-hero__title">运行状态</h1>
      <p class="ch-hero__desc">
        查看当前服务的版本、连接状态与响应信息。
      </p>
    </section>

    <a-spin :loading="store.isLoading" class="ch-spin">
      <a-alert
        v-if="store.state === 'error'"
        type="error"
        class="ch-alert"
        :title="store.errorMessage"
        closable
      >
        <template v-if="store.errorTraceId" #default>
          <div class="ch-alert__body">
            <span>链路 ID：</span>
            <code>{{ store.errorTraceId }}</code>
            <span class="ch-alert__hint">把它提供给后端即可直接在日志中定位这次请求。</span>
          </div>
        </template>
      </a-alert>

      <div v-if="store.state === 'success'" class="ch-card">
        <div class="ch-card__head">
          <h2 class="ch-card__title">运行实例信息</h2>
          <a-space size="small">
            <code v-if="store.lastTraceId" class="ch-trace">trace: {{ store.lastTraceId }}</code>
            <a-button size="small" :loading="store.isLoading" @click="store.loadSystemInfo(true)">
              刷新
            </a-button>
          </a-space>
        </div>

        <a-descriptions :column="{ xs: 1, md: 2 }" bordered size="large">
          <a-descriptions-item
            v-for="row in infoRows"
            :key="row.label"
            :label="row.label"
          >
            <span class="ch-value">{{ row.value }}</span>
          </a-descriptions-item>
        </a-descriptions>
      </div>

      <a-result
        v-else-if="store.state === 'error'"
        status="warning"
        title="未能取到实例信息"
        subtitle="后端可能尚未启动，或 /api 代理未生效。排查顺序见 docs/11-开发环境.md。"
      >
        <template #extra>
          <a-button type="primary" @click="store.loadSystemInfo(true)">重试</a-button>
        </template>
      </a-result>
    </a-spin>


  </div>
</template>

<style scoped>
.ch-page {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ch-hero__title {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.ch-hero__desc {
  margin: 0;
  max-width: 760px;
  color: var(--ch-text-secondary);
}

.ch-spin {
  display: block;
}

.ch-alert {
  margin-bottom: 16px;
}

.ch-alert__body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.ch-alert__hint {
  color: var(--ch-text-tertiary);
}

.ch-card {
  padding: 20px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.ch-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.ch-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ch-trace {
  max-width: 260px;
  overflow: hidden;
  color: var(--ch-text-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ch-value {
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
