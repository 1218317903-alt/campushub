import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref } from 'vue'

import {
  fetchProfile,
  login as loginApi,
  logout as logoutApi,
  refresh as refreshApi,
  register as registerApi,
  type UserProfile,
} from '@/api/auth'
import { ApiError, NetworkError, setAuthBridge } from '@/api/http'

/**
 * 登录态：同页刷新合并，跨标签页使用 Web Locks 串行轮换并同步 storage。
 * 令牌目前仍存 localStorage；后续改用 HttpOnly Cookie 时须同时落实 CSRF 防护。
 */
const ACCESS_TOKEN_KEY = 'camphub.accessToken'
const REFRESH_TOKEN_KEY = 'camphub.refreshToken'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_TOKEN_KEY))
  const refreshToken = ref<string | null>(localStorage.getItem(REFRESH_TOKEN_KEY))
  const profile = ref<UserProfile | null>(null)
  /** 是否已经尝试过用本地令牌恢复会话。用于避免每次路由跳转都重复请求 */
  const restored = ref(false)

  const isLoggedIn = computed(() => accessToken.value !== null)

  /**
   * 正在进行的刷新请求。
   *
   * <p>没有它，同一页面上的三个并发请求在令牌过期时会各自发一次刷新 ——
   * 而服务端把"同一个刷新令牌被用两次"视为**泄露证据**，会撤销该用户全部会话。
   * 也就是说：少了这个去重，"页面刚打开就自动登出"会成为一个稳定复现的 bug。
   */
  let refreshInFlight: Promise<boolean> | null = null
  let sessionRevision = 0

  function persist(tokens: { accessToken: string; refreshToken: string }): void {
    sessionRevision++
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  }

  function clear(): void {
    sessionRevision++
    accessToken.value = null
    refreshToken.value = null
    profile.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  }

  /** 同步其他标签页登录、刷新或登出后的凭据。 */
  function syncStorage(): void {
    const access = localStorage.getItem(ACCESS_TOKEN_KEY)
    const refresh = localStorage.getItem(REFRESH_TOKEN_KEY)
    if (access !== accessToken.value || refresh !== refreshToken.value) {
      sessionRevision++
      accessToken.value = access
      refreshToken.value = refresh
      profile.value = null
    }
  }

  function onStorage(event: StorageEvent): void {
    if (event.key === null || event.key === ACCESS_TOKEN_KEY || event.key === REFRESH_TOKEN_KEY) {
      syncStorage()
    }
  }
  window.addEventListener('storage', onStorage)
  onScopeDispose(() => window.removeEventListener('storage', onStorage))

  /**
   * 仅凭据确定失效时返回 false；网络、限流和服务端故障继续向调用方抛出。
   * 刷新期间发生登出或切换账号时，迟到的响应不能恢复旧会话。
   */
  async function refreshTokens(): Promise<boolean> {
    if (refreshInFlight) return refreshInFlight
    const requestedAccess = accessToken.value
    const rotate = async (): Promise<boolean> => {
      syncStorage()
      if (accessToken.value && accessToken.value !== requestedAccess) return true
      const current = refreshToken.value
      if (!current) return false
      const revision = sessionRevision
      try {
        const { data } = await refreshApi(current)
        syncStorage()
        if (sessionRevision !== revision) {
          // 已切换会话，回收这次迟到的刷新凭据。
          void logoutApi(data.refreshToken).catch(() => undefined)
          return accessToken.value !== null
        }
        persist(data)
        return true
      } catch (error) {
        syncStorage()
        if (sessionRevision !== revision) return accessToken.value !== null
        if (error instanceof ApiError && (error.status === 401 || error.code === 40301)) {
          return false
        }
        throw error
      }
    }
    const pending = typeof navigator !== 'undefined' && navigator.locks
      ? navigator.locks.request('camphub.refresh', rotate)
      : rotate()
    refreshInFlight = pending
    try {
      return await pending
    } finally {
      if (refreshInFlight === pending) refreshInFlight = null
    }
  }

  /**
   * 装载用户资料。
   *
   * @returns 成功返回 true
   */
  async function loadProfile(): Promise<boolean> {
    if (!accessToken.value) {
      return false
    }
    try {
      const { data } = await fetchProfile()
      profile.value = data
      return true
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clear()
      }
      return false
    }
  }

  /**
   * 恢复会话：本地有令牌就试着取一次资料。
   *
   * <p>刷新由 HTTP 层在真正需要时触发（收到 40101），这里不预先刷新 ——
   * 页面加载就刷新会让"只是打开首页看一眼"也产生一次令牌轮换，
   * 而每一次轮换都是一次重放检测的机会。
   *
   * @returns 恢复成功返回 true
   */
  async function restore(): Promise<boolean> {
    if (restored.value) {
      return isLoggedIn.value
    }
    restored.value = true
    if (!accessToken.value) {
      return false
    }
    return loadProfile()
  }

  /**
   * 登录。
   *
   * @param identifier 用户名或邮箱
   * @param password   密码
   */
  async function login(identifier: string, password: string): Promise<void> {
    const { data } = await loginApi(identifier, password)
    persist(data)
    await loadProfile()
  }

  /**
   * 注册（成功后即为登录状态）。
   *
   * @param username 登录名
   * @param email    邮箱
   * @param password 密码
   * @param nickname 昵称，可空
   */
  async function register(
    username: string,
    email: string,
    password: string,
    nickname?: string,
  ): Promise<void> {
    const { data } = await registerApi(username, email, password, nickname)
    persist(data)
    await loadProfile()
  }

  /**
   * 登出。
   *
   * <p>先保存待撤销的凭据并清本地状态，再通知服务端。
   * 通知失败不阻断本地登出；迟到的刷新响应也不能重新建立会话。
   */
  async function logout(): Promise<void> {
    const current = refreshToken.value
    clear()
    if (current) {
      try {
        await logoutApi(current)
      } catch (error) {
        // 只在网络层面提醒，不影响"已经在本地登出"这个结论
        if (error instanceof NetworkError) {
          console.warn('登出请求未能送达服务端，该会话将在其有效期内保持有效', error.message)
        }
      }
    }
  }

  // 把认证能力交给 HTTP 层。放在 store 定义内而不是模块顶层：
  // 模块顶层只会在首次 import 时执行一次，而 store 可能被销毁重建（测试中尤其明显）
  setAuthBridge({
    accessToken: () => accessToken.value,
    refreshTokens,
    onSessionLost: () => clear(),
  })

  return {
    accessToken,
    refreshToken,
    profile,
    isLoggedIn,
    restored,
    restore,
    loadProfile,
    login,
    register,
    logout,
  }
})
