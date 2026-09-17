import { get, post } from './http'

/**
 * 认证接口。
 *
 * <h2>为什么所有请求都显式带 {@code device}</h2>
 * 服务端的会话列表按设备展示，目的是让用户能识别"这是不是我自己的登录"。
 * 一个为空或恒为固定值的设备标识会让那个列表失去全部意义 ——
 * 用户看到五个"未知设备"时唯一能做的就是全部下线。
 * 这里传的是浏览器上报的 UA 摘要，是客户端能提供的最有辨识度的信息。
 */

/** 登录 / 注册 / 刷新 的成功响应。字段与后端 `IssuedTokensResponse` 一一对应。 */
export interface IssuedTokens {
  accessToken: string
  /** 刷新令牌只在这一次响应中出现，服务端只保存它的哈希，无法再次读出 */
  refreshToken: string
  tokenType: string
  /** 访问令牌有效期（秒）。客户端据此提前刷新，而不是等失败后再补救 */
  expiresIn: number
}

/** 当前用户资料。字段与后端 `UserProfileResponse` 一一对应。 */
export interface UserProfile {
  publicId: string
  username: string
  nickname: string
  avatarUrl: string | null
  bio: string | null
  createdAt: string
}

/**
 * 生成设备标识。
 *
 * 取 UA 的前若干字符并压缩空白：够用来区分"同一台电脑上的 Chrome / Safari"，
 * 又不至于把整条 UA（可能上百字符）塞进数据库的 64 字符列。
 *
 * @returns 设备标识
 */
export function deviceLabel(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent
  const collapsed = ua.replace(/\s+/g, ' ').trim()
  return collapsed.length > 60 ? collapsed.slice(0, 60) : collapsed || '未知设备'
}

/**
 * 登录。
 *
 * @param identifier 用户名或邮箱（服务端忽略大小写）
 * @param password   密码
 * @returns 令牌对
 */
export function login(identifier: string, password: string) {
  return post<IssuedTokens>('/v1/auth/login', {
    identifier,
    password,
    device: deviceLabel(),
  })
}

/**
 * 注册（成功后服务端直接建立会话，无需再登录一次）。
 *
 * @param username 登录名
 * @param email    邮箱
 * @param password 密码
 * @param nickname 昵称，可为空（为空时服务端用登录名）
 * @returns 令牌对
 */
export function register(username: string, email: string, password: string, nickname?: string) {
  return post<IssuedTokens>('/v1/auth/register', {
    username,
    email,
    password,
    nickname: nickname ?? '',
    device: deviceLabel(),
  })
}

/**
 * 用刷新令牌换取新的令牌对。
 *
 * <p>刻意传当前设备标识：服务端会据此判断"这次刷新来自哪个设备"，
 * 而刷新令牌轮换后新令牌仍归属同一会话。
 *
 * @param refreshToken 刷新令牌
 * @returns 新的令牌对
 */
export function refresh(refreshToken: string) {
  return post<IssuedTokens>('/v1/auth/refresh', {
    refreshToken,
    device: deviceLabel(),
  })
}

/**
 * 登出（撤销该设备的刷新令牌）。
 *
 * <p>登出接口是公开的：它撤销的是刷新令牌，而"持有刷新令牌"本身就是授权。
 * 若要求访问令牌有效，就会出现"访问令牌刚过期、想登出却被 401 拦住"的死角。
 *
 * @param refreshToken 要撤销的刷新令牌
 */
export function logout(refreshToken: string) {
  return post<void>('/v1/auth/logout', { refreshToken })
}

/**
 * 查询当前用户资料。
 *
 * @returns 资料
 */
export function fetchProfile() {
  return get<UserProfile>('/v1/users/me')
}
