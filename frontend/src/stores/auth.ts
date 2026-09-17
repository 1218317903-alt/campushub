import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

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
 * 登录态。
 *
 * <h2>令牌放在哪里，以及为什么</h2>
 * 访问令牌（15 分钟）与刷新令牌（30 天）都放在 {@code localStorage}。这是一个
 * <b>明确知情的取舍</b>，不是没想过：
 * <ul>
 *   <li>好处是"关掉浏览器再打开仍然是登录状态" —— 用户对社区的默认预期，
 *       而刷新令牌的有效期本来就是 30 天，放内存里等于把它废掉。</li>
 *   <li>代价是 XSS 一旦发生，攻击者可以直接取走刷新令牌，拿到长期会话。
 *       当前的风险面被两件事压住：正文由服务端渲染并净化后才输出（本仓库里
 *       前端没有任何 `v-html` 渲染用户输入的地方），评论以文本节点渲染。
 *       但这只是"降低了发生概率"，不是"消除了后果"。</li>
 *   <li><b>正确的收口方式是</b>把刷新令牌改为 {@code httpOnly} Cookie 并由服务端下发，
 *       同时补上 CSRF 防护（后端 {@code SecurityConfig} 里已经写明"若把刷新令牌
 *       改放进 Cookie，CSRF 那一条必须同步改回来"）。那时访问令牌可以只放内存，
 *       因为它 15 分钟就换一次，不需要持久化。这件事记在
 *       {@code docs/architecture.md} 的已知限制里，不作为本阶段的遗留盲点。</li>
 * </ul>
 *
 * <h2>为什么刷新逻辑要在这里而不是在 HTTP 层</h2>
 * HTTP 层不知道"刷新令牌存在哪、刷新失败之后要做什么"。它只负责在收到
 * "令牌过期"时回调这里，由这里决定是换取新令牌还是判定会话结束。
 * 依赖方向因此是单向的：store → http（注册桥），http → 接口（回调）。
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

  function persist(tokens: { accessToken: string; refreshToken: string }): void {
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  }

  function clear(): void {
    accessToken.value = null
    refreshToken.value = null
    profile.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  }

  /**
   * 用刷新令牌换取新令牌。
   *
   * @returns 成功返回 true；刷新令牌缺失或已失效返回 false
   */
  async function refreshTokens(): Promise<boolean> {
    if (refreshInFlight) {
      return refreshInFlight
    }
    const current = refreshToken.value
    if (!current) {
      return false
    }

    refreshInFlight = (async () => {
      try {
        const { data } = await refreshApi(current)
        persist(data)
        return true
      } catch {
        // 刷新失败的原因不在此处区分：无论是令牌被撤销、过期，还是网络不通，
        // 对调用方而言结论都是"这次拿不到新令牌"。网络问题由随后的请求自己报错，
        // 这里不去猜 —— 猜错会导致把用户的登录态误清掉
        return false
      } finally {
        refreshInFlight = null
      }
    })()

    return refreshInFlight
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
   * <p>先尽力通知服务端撤销刷新令牌，再清本地状态。顺序不能反：
   * 本地状态一清，刷新令牌就没了，服务端那条会话会一直有效到自然过期。
   * 通知失败不阻断登出 —— 用户点了登出就必须登出，哪怕网络不通。
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
