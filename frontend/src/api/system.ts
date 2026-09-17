import { get } from './http'

/**
 * 后端 `/api/v1/system/info` 的响应。
 * 字段与后端 `SystemInfoResponse` record 一一对应。
 */
export interface SystemInfo {
  application: string
  version: string
  /** 生效的 Spring Profile，例如 "local" */
  profiles: string
  javaVersion: string
  /** ISO-8601 时间串（后端以 Instant 序列化，带 Z 后缀） */
  serverTime: string
  /** 数据库 schema 基线版本，来自 `app_metadata` 表；为空说明迁移未生效 */
  schemaBaseline: string
}

/**
 * 查询后端实例信息。
 *
 * 这是 Phase 01 的「纵向切片」：一个请求从 Vue 页面出发，
 * 经 dev proxy → Controller → Service → MyBatis → MySQL(`app_metadata`) → 再回到页面。
 * 它同时验证了 HTTP 层、统一错误契约、配置管理、Flyway 迁移与数据访问链路都是通的。
 */
export function fetchSystemInfo(): Promise<{ data: SystemInfo; traceId: string | null }> {
  return get<SystemInfo>('/v1/system/info')
}
