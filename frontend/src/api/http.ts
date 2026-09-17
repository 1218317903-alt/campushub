import type { ApiErrorBody, ApiFieldViolation } from './types'

/**
 * API 前缀。默认走同源相对路径 `/api`，由 Vite dev proxy（开发）或 Nginx（生产）转发。
 * 前端代码里**不应该**出现 `http://host:port` 这样的绝对地址。
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api'

/** 后端返回的结构化错误。与后端 `ApiError` 字段一一对应。 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId: string
  readonly status: number
  readonly path: string
  readonly timestamp: string
  readonly details: readonly ApiFieldViolation[]

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
    this.path = body.path
    this.timestamp = body.timestamp
    this.details = body.details
  }

  /**
   * 是否属于「服务端未预期异常」。
   * UI 可以据此决定是否展示「请稍后重试」并附上 traceId 供排查。
   */
  get isServerFault(): boolean {
    return this.status >= 500
  }

  /**
   * 字段级错误 → `{ 字段名: 提示 }` 映射，便于直接绑定到表单的 label 上。
   * 同一字段出现多次时保留第一条（后端已按字段去重，这里是防御性处理）。
   */
  fieldMessages(): Record<string, string> {
    const result: Record<string, string> = {}
    for (const item of this.details) {
      if (!(item.field in result)) {
        result[item.field] = item.reason
      }
    }
    return result
  }
}

/**
 * 网络层失败（连不上、超时、响应不是 JSON）。与「服务端返回了错误」区分开。
 *
 * <p>不额外声明 `cause` 字段：`Error` 在 ES2022 起已内建该属性，
 * 直接通过构造函数的 options 传入即可，重复声明反而需要 `override` 修饰并增加一处状态。
 */
export class NetworkError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause })
    this.name = 'NetworkError'
  }
}

export interface RequestOptions {
  /** 查询参数；值为 undefined / null 的键会被跳过 */
  query?: Record<string, string | number | boolean | undefined | null>
  /** 请求体，会被 JSON 序列化；GET/HEAD 请勿传 */
  body?: unknown
  /** 超时（毫秒），默认 10s。超时抛 NetworkError，不抛 ApiError —— 它不是服务端的判定。 */
  timeoutMs?: number
  /** 额外的请求头（例如后续阶段的 Authorization） */
  headers?: Record<string, string>
  /** 外部传入的取消信号，会与内部超时信号合并 */
  signal?: AbortSignal
}

/** 成功响应 + 链路 ID。把 traceId 一并返回，方便用户报错时直接给出。 */
export interface ApiResult<T> {
  data: T
  traceId: string | null
}

/**
 * 认证桥。
 *
 * <h2>为什么是"桥"而不是直接 import auth store</h2>
 * 认证状态在 Pinia store 里，而 store 需要调用 HTTP 层发请求 —— 如果 HTTP 层反过来
 * 直接 import store，就形成一个模块级循环依赖。通过注册一个接口对象，
 * 依赖方向变成单向：store → http（注册实现），http → 接口（调用）。
 *
 * <p>它同时让本层可以在没有认证能力的情况下工作（例如未登录时浏览公开内容），
 * 因此这个桥是可选的：没有注册时，请求就是匿名请求。
 */
export interface AuthBridge {
  /** 当前访问令牌；未登录时返回 null */
  accessToken(): string | null
  /**
   * 尝试用刷新令牌换一对新令牌。
   *
   * @returns 刷新成功返回 true；刷新令牌也失效时返回 false（此时调用方应引导重新登录）
   */
  refreshTokens(): Promise<boolean>
  /** 确认会话已失效（刷新失败），由 store 清理本地状态 */
  onSessionLost(): void
}

let authBridge: AuthBridge | null = null

/**
 * 注册认证桥。由 auth store 在初始化时调用一次。
 *
 * @param bridge 认证桥；传 null 可解除（主要用于测试）
 */
export function setAuthBridge(bridge: AuthBridge | null): void {
  authBridge = bridge
}

const DEFAULT_TIMEOUT_MS = 10_000

/**
 * 是否需要"刷新令牌后重试"。
 *
 * <p>只对<b>令牌过期</b>（40101）这么做。原因：
 * <ul>
 *   <li>{@code 40100}（未登录）说明请求里根本没带令牌 —— 刷新解决不了，
 *       而且会让未登录用户去触发一次必然失败的刷新请求。</li>
 *   <li>{@code 40102}/{@code 40104}（令牌无效/已失效）说明这个会话已经被判定不可信，
 *       刷新同样会被拒；引导重新登录才是正确的动作。</li>
 * </ul>
 * 把"过期"与"无效"分开处理，是后端把它们分成两个错误码的唯一理由 ——
 * 这里正是那个理由的兑现处。
 */
const REFRESHABLE_AUTH_CODE = 40101

/** 认证入口自身不参与"刷新后重试"，否则刷新失败会递归触发刷新 */
const AUTH_ENTRY_PATHS = ['/v1/auth/login', '/v1/auth/register', '/v1/auth/refresh', '/v1/auth/logout']

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) {
    return url
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) {
      params.append(key, String(value))
    }
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

/** 判断响应体是否长得像后端的 ApiError，避免把网关返回的 HTML 错误页当成结构化错误。 */
function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'number' &&
    typeof candidate.message === 'string' &&
    typeof candidate.traceId === 'string' &&
    Array.isArray(candidate.details)
  )
}

/**
 * 发起一次 API 请求。
 *
 * <b>关于「自动重试」</b>：这里刻意<b>不</b>做通用自动重试。
 * 对非幂等请求（POST）无条件重试会产生重复副作用，是正确的 bug 制造机。
 * 需要重试语义的调用方应当在明确知道自身幂等的前提下自行重试。
 *
 * <p><b>唯一的例外是「访问令牌过期后刷新并重试一次」</b>，它不算通用重试：
 * 那个请求根本没被业务逻辑处理过（在安全过滤链就被拒了），因此重试它不产生任何
 * 副作用。没有这一步，用户会在访问令牌到期的瞬间（默认 15 分钟）看到一个
 * 莫名其妙的失败，而他本来什么都不用做 —— 前端也就此失去"静默续期"的能力。
 *
 * @param method HTTP 方法
 * @param path   以 `/` 开头、**不含** `/api` 前缀的路径，例如 `/v1/system/info`
 * @param options 请求选项
 * @returns 成功时的数据与链路 ID
 * @throws ApiError     服务端返回了结构化错误
 * @throws NetworkError 网络失败、超时，或响应无法解析为 JSON
 */
export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  options: RequestOptions = {},
): Promise<ApiResult<T>> {
  const first = await send<T>(method, path, options)
  if (!(first.error instanceof ApiError)) {
    if (first.error) {
      throw first.error
    }
    return { data: first.data as T, traceId: first.traceId }
  }

  const error = first.error
  // 先取到局部常量：authBridge 是模块级变量，可能在 await 之间被改写（例如用户登出），
  // 而「类型收窄」也无法穿过一个布尔变量保存下来 —— 所以下面还要再判一次 null
  const bridge = authBridge
  const refreshable =
    error.code === REFRESHABLE_AUTH_CODE &&
    bridge !== null &&
    !AUTH_ENTRY_PATHS.some((entry) => path.startsWith(entry))

  if (!refreshable || bridge === null) {
    throw error
  }

  const refreshed = await bridge.refreshTokens()
  if (!refreshed) {
    // 刷新令牌也失效了：这个会话已经结束。由 store 负责清理本地状态并给出提示，
    // 这里只保证调用方拿到明确的结果，而不是一个"看起来像网络问题"的失败
    bridge.onSessionLost()
    throw error
  }

  const second = await send<T>(method, path, options)
  if (second.error) {
    throw second.error
  }
  return { data: second.data as T, traceId: second.traceId }
}

/** 单次请求的结果。用返回值而不是抛异常传递失败，是为了让重试逻辑读起来是一条直线。 */
interface SendResult<T> {
  data?: T
  traceId: string | null
  error?: ApiError | NetworkError
}

/**
 * 发送一次请求（不含重试）。
 *
 * @param method  HTTP 方法
 * @param path    路径
 * @param options 请求选项
 * @returns 结果
 */
async function send<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  options: RequestOptions,
): Promise<SendResult<T>> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  const signal = options.signal
    ? AbortSignal.any([options.signal, timeoutSignal])
    : timeoutSignal

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
  }

  // 已登录时自动带上令牌。调用方不需要、也不应该在每个请求里手动拼 Authorization ——
  // 那样迟早会有一个请求漏掉，而它的表现是"这个功能偶尔要求重新登录"
  const accessToken = authBridge?.accessToken() ?? null
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`
  }

  let payload: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      ...(payload === undefined ? {} : { body: payload }),
      signal,
    })
  } catch (error) {
    const aborted = options.signal?.aborted ?? false
    return {
      traceId: null,
      error: new NetworkError(
        aborted ? '请求已取消' : `无法连接到服务器（${timeoutMs} ms 内未响应）`,
        error,
      ),
    }
  }

  const traceId = response.headers.get('X-Trace-Id')

  // 204 / 205 无响应体
  if (response.status === 204 || response.status === 205) {
    return { data: undefined as T, traceId }
  }

  let parsed: unknown
  try {
    parsed = await response.json()
  } catch (error) {
    return {
      traceId,
      error: new NetworkError('服务器返回的内容不是合法 JSON', error),
    }
  }

  if (!response.ok) {
    if (isApiErrorBody(parsed)) {
      return { traceId, error: new ApiError(response.status, parsed) }
    }
    // 后端契约之外的错误（例如反向代理自己产生的 502 页面）
    return {
      traceId,
      error: new NetworkError(`请求失败（HTTP ${response.status}），且响应不符合统一错误契约`),
    }
  }

  return { data: parsed as T, traceId }
}

/** 便捷方法：GET */
export function get<T>(path: string, options: Omit<RequestOptions, 'body'> = {}): Promise<ApiResult<T>> {
  return request<T>('GET', path, options)
}

/**
 * 便捷方法：POST。
 *
 * @param path    路径
 * @param body    请求体；不需要请求体的操作（例如点赞）传 undefined
 * @param options 其余选项
 */
export function post<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'body'> = {},
): Promise<ApiResult<T>> {
  return request<T>('POST', path, { ...options, body })
}

/**
 * 便捷方法：PUT。
 *
 * @param path    路径
 * @param body    请求体
 * @param options 其余选项
 */
export function put<T>(
  path: string,
  body: unknown,
  options: Omit<RequestOptions, 'body'> = {},
): Promise<ApiResult<T>> {
  return request<T>('PUT', path, { ...options, body })
}

/**
 * 便捷方法：DELETE。
 *
 * <p>没有请求体参数 —— 本项目的删除类接口都不需要 body（要删什么由路径表达）。
 * 留一个 body 参数只会让人以为可以传，而它实际不会被用到。
 *
 * @param path    路径
 * @param options 其余选项
 */
export function del<T>(
  path: string,
  options: Omit<RequestOptions, 'body'> = {},
): Promise<ApiResult<T>> {
  return request<T>('DELETE', path, options)
}
