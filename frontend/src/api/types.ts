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

/**
 * 分页响应。字段与后端 `PageResponse` 一一对应。
 *
 * <h2>为什么它在这里，而不是在某个业务模块里</h2>
 * 社区的帖子列表、空间的成员/笔记/文档列表用的是<b>同一个</b>分页形状，
 * 由后端同一个 `PageResponse` 序列化而来。把它定义在社区模块里、让其他模块
 * 反向 import，会让"社区换了分页字段"看起来像是只影响社区的一件事。
 * 通用的形状放在通用的文件里，依赖方向才与事实一致。
 */
export interface PageResponse<T> {
  items: T[]
  /** 从 1 开始。后端已归一化，前端不需要再判断 0 */
  page: number
  /** 实际生效的页大小，可能小于请求值（服务端有上限） */
  size: number
  total: number
  hasNext: boolean
}
