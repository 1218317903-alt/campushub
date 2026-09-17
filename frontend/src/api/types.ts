/**
 * 后端统一错误响应体的前端镜像。
 *
 * <b>为什么在 TS 里手写而不是自动生成</b>：
 * 这份结构在 Phase 01 已经冻结（见后端 `common/error/ApiError.java`），字段极少且稳定。
 * 等接口数量上来、契约开始频繁变动时，再引入 openapi-typescript 从 `/v3/api-docs` 生成，
 * 那时自动化的收益才大于维护成本。现在提前引入只会多一条构建链路要维护。
 *
 * 注意：**后端成功响应不套信封**，所以这里只有「错误」一种结构，没有 `ApiResponse<T>`。
 */
export interface ApiFieldViolation {
  /** 出错字段名（可能是 `items[0].title` 这类路径） */
  field: string
  /** 该字段为何不合法 */
  reason: string
}

export interface ApiErrorBody {
  /** 5 位业务错误码，规则见后端 `ErrorCode`：HHH SS = HTTP 状态码 + 序号 */
  code: number
  /** 面向用户的可读文案 */
  message: string
  /** 链路 ID，与后端日志中的 traceId 一致 */
  traceId: string
  /** ISO-8601 UTC 时间戳 */
  timestamp: string
  /** 出错的请求路径 */
  path: string
  /** 字段级错误明细；后端保证恒为数组（可能为空），前端无需判空 */
  details: ApiFieldViolation[]
}
