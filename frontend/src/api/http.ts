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

const DEFAULT_TIMEOUT_MS = 10_000

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
 * <b>关于「自动重试」</b>：这里刻意<b>不</b>做全局自动重试。
 * 对非幂等请求（POST）无条件重试会产生重复副作用，是正确的 bug 制造机。
 * 需要重试语义的调用方应当在明确知道自身幂等的前提下自行重试。
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
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  const signal = options.signal
    ? AbortSignal.any([options.signal, timeoutSignal])
    : timeoutSignal

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
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
    throw new NetworkError(
      aborted ? '请求已取消' : `无法连接到服务器（${timeoutMs} ms 内未响应）`,
      error,
    )
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
    throw new NetworkError('服务器返回的内容不是合法 JSON', error)
  }

  if (!response.ok) {
    if (isApiErrorBody(parsed)) {
      throw new ApiError(response.status, parsed)
    }
    // 后端契约之外的错误（例如反向代理自己产生的 502 页面）
    throw new NetworkError(`请求失败（HTTP ${response.status}），且响应不符合统一错误契约`)
  }

  return { data: parsed as T, traceId }
}

/** 便捷方法：GET */
export function get<T>(path: string, options: Omit<RequestOptions, 'body'> = {}): Promise<ApiResult<T>> {
  return request<T>('GET', path, options)
}
