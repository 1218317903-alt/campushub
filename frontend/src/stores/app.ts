import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { ApiError, NetworkError } from '@/api/http'
import { fetchSystemInfo, type SystemInfo } from '@/api/system'

export type LoadState = 'idle' | 'loading' | 'success' | 'error'

/**
 * 应用级状态。
 *
 * 目前只承担一件事：把后端实例信息加载出来，并把这三种结果**区分开**——
 * `ApiError`（服务端明确拒绝）、`NetworkError`（连不上/超时）、成功。
 * 这三者在 UI 上应当有完全不同的措辞，混成一句「加载失败」会让真实故障无法定位。
 *
 * Workspace / 用户身份等状态在各自 Phase 引入，不要在这里提前堆放。
 */
export const useAppStore = defineStore('app', () => {
  const state = ref<LoadState>('idle')
  const info = ref<SystemInfo | null>(null)
  const errorMessage = ref<string>('')
  /** 出错时的链路 ID；把它显示给用户，报障时可以直接对上后端日志 */
  const errorTraceId = ref<string>('')
  /** 最近一次成功请求的链路 ID */
  const lastTraceId = ref<string>('')

  const isLoading = computed(() => state.value === 'loading')

  async function loadSystemInfo(force = false): Promise<void> {
    if (state.value === 'loading') {
      return
    }
    if (state.value === 'success' && !force) {
      return
    }

    state.value = 'loading'
    errorMessage.value = ''
    errorTraceId.value = ''

    try {
      const { data, traceId } = await fetchSystemInfo()
      info.value = data
      lastTraceId.value = traceId ?? ''
      state.value = 'success'
    } catch (error) {
      if (error instanceof ApiError) {
        errorMessage.value = error.message
        errorTraceId.value = error.traceId
      } else if (error instanceof NetworkError) {
        errorMessage.value = `${error.message}。请确认后端已启动（默认 127.0.0.1:8080）。`
      } else {
        errorMessage.value = '发生未知错误'
      }
      state.value = 'error'
    }
  }

  return {
    state,
    info,
    isLoading,
    errorMessage,
    errorTraceId,
    lastTraceId,
    loadSystemInfo,
  }
})
