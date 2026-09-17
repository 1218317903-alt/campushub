/// <reference types="vite/client" />

/**
 * 环境变量类型声明。
 *
 * 只有声明在这里的变量才允许通过 import.meta.env 访问 —— 这样拼错的变量名会在
 * 编译期报错，而不是在运行时静默变成 undefined。
 */
interface ImportMetaEnv {
  /** 后端 API 前缀，默认 `/api`（同源相对路径，由 dev server 或反向代理转发） */
  readonly VITE_API_BASE_URL?: string
  /** 仅开发环境使用：dev server 代理的目标后端地址 */
  readonly VITE_DEV_BACKEND_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
