import { del, get, post, put, uploadFile } from './http'
import type { PageResponse } from './types'

/**
 * 空间接口：空间、成员、定向邀请、协作笔记与文档。
 *
 * <h2>所有路径都带空间标识，这是刻意的</h2>
 * 后端把"文档在哪个空间里"编进了 URL，而不是让客户端只给一个文档标识。
 * 原因是定位方式本身就是一道防线：一件资源由 {@code (空间, 标识)} 成对定位，
 * 于是"拿着别的空间的标识去试"这件事在路由层面就无法表达。
 * 前端照着契约拼路径即可 —— 不要为了少一个参数把它改成一个全局标识，
 * 那会与后端已落地的判定方式不一致。
 *
 * <h2>为什么下载与分享都走 download-link</h2>
 * 后端有两条下载路径：带令牌的 {@code /content} 与短期链接 {@code /download-link}。
 * 前者需要前端用 blob 请求再自己触发保存（并处理文件名编码），
 * 后者直接把"一个可用的地址"交给浏览器，且<b>两种存储后端的行为一致</b>
 * （本地磁盘返回本应用的签名地址，对象存储返回预签名直链）。
 * 用一条路径服务两种后端，比在前端判断"当前是哪种后端"要好得多。
 */

/** 展示用的用户信息。账号已注销时 `publicId` 与 `avatarUrl` 为 null。 */
export interface Contributor {
  publicId: string | null
  nickname: string
  avatarUrl: string | null
}

/** 空间可见性。两者的授权判定目前相同，取值差异只表达意图。 */
export type WorkspaceVisibility = 'PRIVATE' | 'TEAM'

/** 当前用户在空间内的角色。 */
export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER'

/** 空间（含"我"在其中的角色）。 */
export interface Workspace {
  publicId: string
  name: string
  description: string | null
  visibility: WorkspaceVisibility
  myRole: WorkspaceRole
  createdAt: string
  updatedAt: string
}

/** 空间成员。 */
export interface Member {
  userPublicId: string
  nickname: string
  avatarUrl: string | null
  role: WorkspaceRole
  joinedAt: string
}

/** 邀请状态。 */
export type InviteStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

/**
 * 定向邀请。
 *
 * <h2>列表里为什么要显示 `code`</h2>
 * 本项目还没有通知机制（属后续阶段），邀请码需要邀请人自行转达。
 * 把码显示出来，是为了让"邀请"这件事在当前阶段真的能用 ——
 * 否则被邀请人永远收不到那串码。
 */
export interface Invite {
  code: string
  invitee: Contributor
  role: WorkspaceRole
  status: InviteStatus
  expiresAt: string
  createdAt: string
}

/** 笔记列表卡片。**不含正文**。 */
export interface NoteCard {
  publicId: string
  title: string
  summary: string
  author: Contributor
  editor: Contributor
  /** 由服务端判定并下发 —— 不显示一个点下去会 403 的按钮 */
  deletableByMe: boolean
  createdAt: string
  updatedAt: string
}

/** 笔记详情。 */
export interface NoteDetail extends NoteCard {
  bodyMd: string
  /** 服务端渲染并净化后的 HTML。前端直接插入，**不做二次渲染或转义** */
  bodyHtml: string
}

/** 解析状态。`PROCESSING` 是 worker 已经领走任务的唯一用户可见证据。 */
export type ParseStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'

/** 文档元数据。 */
export interface DocumentItem {
  publicId: string
  name: string
  mimeType: string
  sizeBytes: number
  parseStatus: ParseStatus
  /** 0~100。服务端在领取任务时给出 5，成功时给 100 */
  parseProgress: number
  /** 面向用户的失败原因，不含内部堆栈；成功时为 null */
  parseMessage: string | null
  chunkCount: number
  textLength: number
  parsedAt: string | null
  uploader: Contributor
  deletableByMe: boolean
  retryableByMe: boolean
  createdAt: string
  updatedAt: string
}

/** 解析分块。用于核对解析质量（顺序、标题路径、块大小）。 */
export interface DocumentChunk {
  /** 自 0 连续递增 */
  ordinal: number
  /** 所属标题路径，如 `空间文档规范 > 支持的格式`；纯文本与 PDF 有自己的取值 */
  heading: string | null
  content: string
  charCount: number
}

/** 短期下载链接。 */
export interface DownloadLink {
  /** 可直接使用的地址。`direct` 为 true 时是对象存储的预签名直链，字节不经过应用 */
  url: string
  direct: boolean
  expiresAt: string
}

// ---------------------------------------------------------------------------
// 空间
// ---------------------------------------------------------------------------

/**
 * 我的空间列表。
 *
 * @param page 页码，从 1 开始
 * @param size 页大小
 */
export function listWorkspaces(page = 1, size = 20) {
  return get<PageResponse<Workspace>>('/v1/workspaces', { query: { page, size } })
}

/**
 * 创建空间。
 *
 * @param body 名称、描述与可见性
 */
export function createWorkspace(body: {
  name: string
  description?: string | null
  visibility?: WorkspaceVisibility
}) {
  return post<Workspace>('/v1/workspaces', body)
}

/** 空间详情。 */
export function getWorkspace(publicId: string) {
  return get<Workspace>(`/v1/workspaces/${publicId}`)
}

/**
 * 修改空间设置（仅拥有者）。
 *
 * @param publicId 空间对外标识
 * @param body     名称、描述与可见性
 */
export function updateWorkspace(
  publicId: string,
  body: { name: string; description?: string | null; visibility?: WorkspaceVisibility },
) {
  return put<Workspace>(`/v1/workspaces/${publicId}`, body)
}

/** 删除空间（仅拥有者）。 */
export function deleteWorkspace(publicId: string) {
  return del<void>(`/v1/workspaces/${publicId}`)
}

// ---------------------------------------------------------------------------
// 成员与邀请
// ---------------------------------------------------------------------------

/** 成员列表。不分页：数量受 `max-members` 约束。 */
export function listMembers(publicId: string) {
  return get<Member[]>(`/v1/workspaces/${publicId}/members`)
}

/** 退出空间。拥有者不能退出 —— 那会留下一个无人能管理的孤儿。 */
export function leaveWorkspace(publicId: string) {
  return del<void>(`/v1/workspaces/${publicId}/members/me`)
}

/** 移除成员（仅拥有者与管理员）。 */
export function removeMember(publicId: string, userPublicId: string) {
  return del<void>(`/v1/workspaces/${publicId}/members/${userPublicId}`)
}

/** 修改成员角色（仅拥有者与管理员）。 */
export function updateMemberRole(publicId: string, userPublicId: string, role: WorkspaceRole) {
  return put<void>(`/v1/workspaces/${publicId}/members/${userPublicId}/role`, { role })
}

/** 邀请列表（待处理与历史）。 */
export function listInvites(publicId: string) {
  return get<Invite[]>(`/v1/workspaces/${publicId}/invites`)
}

/**
 * 邀请一个已注册用户。
 *
 * @param publicId 空间对外标识
 * @param body     被邀请人登录名与角色（省略角色时按普通成员）
 */
export function createInvite(publicId: string, body: { username: string; role?: WorkspaceRole }) {
  return post<Invite>(`/v1/workspaces/${publicId}/invites`, body)
}

/** 撤销邀请。 */
export function revokeInvite(publicId: string, code: string) {
  return del<void>(`/v1/workspaces/${publicId}/invites/${code}`)
}

/**
 * 兑换邀请码。
 *
 * <p>路径里**不含空间标识**：码本身就编码了它是发给谁的、属于哪个空间。
 */
export function acceptInvite(code: string) {
  return post<Workspace>(`/v1/workspace-invites/${code}/accept`)
}

// ---------------------------------------------------------------------------
// 笔记
// ---------------------------------------------------------------------------

/** 笔记列表。 */
export function listNotes(publicId: string, page = 1, size = 20) {
  return get<PageResponse<NoteCard>>(`/v1/workspaces/${publicId}/notes`, { query: { page, size } })
}

/**
 * 新建笔记。
 *
 * @param publicId 空间对外标识
 * @param body     标题与 Markdown 正文
 */
export function createNote(publicId: string, body: { title: string; bodyMd: string }) {
  return post<NoteDetail>(`/v1/workspaces/${publicId}/notes`, body)
}

/** 笔记详情（含 Markdown 原文与渲染后的 HTML）。 */
export function getNote(publicId: string, notePublicId: string) {
  return get<NoteDetail>(`/v1/workspaces/${publicId}/notes/${notePublicId}`)
}

/**
 * 编辑笔记。
 *
 * <p>成员可以编辑空间内任何一篇笔记 —— 这正是"协作笔记"的含义。
 */
export function updateNote(
  publicId: string,
  notePublicId: string,
  body: { title: string; bodyMd: string },
) {
  return put<NoteDetail>(`/v1/workspaces/${publicId}/notes/${notePublicId}`, body)
}

/** 删除笔记。作者本人，或拥有者/管理员。 */
export function deleteNote(publicId: string, notePublicId: string) {
  return del<void>(`/v1/workspaces/${publicId}/notes/${notePublicId}`)
}

// ---------------------------------------------------------------------------
// 文档
// ---------------------------------------------------------------------------

/** 文档列表，按上传时间倒序。 */
export function listDocuments(publicId: string, page = 1, size = 20) {
  return get<PageResponse<DocumentItem>>(`/v1/workspaces/${publicId}/documents`, {
    query: { page, size },
  })
}

/**
 * 上传文档。
 *
 * <p>表单字段名固定为 `file`（后端 `DocumentController` 里的常量）。
 * 这里不额外传大小之类的字段：后端只认 multipart 自己解析出的长度与哈希，
 * 客户端声明的值不参与登记。
 *
 * @param publicId 空间对外标识
 * @param file     文件
 */
export function uploadDocument(publicId: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return uploadFile<DocumentItem>(`/v1/workspaces/${publicId}/documents`, form)
}

/** 文档元数据。 */
export function getDocument(publicId: string, docPublicId: string) {
  return get<DocumentItem>(`/v1/workspaces/${publicId}/documents/${docPublicId}`)
}

/** 删除文档。作者本人，或拥有者/管理员。 */
export function deleteDocument(publicId: string, docPublicId: string) {
  return del<void>(`/v1/workspaces/${publicId}/documents/${docPublicId}`)
}

/**
 * 生成一个短期下载链接。
 *
 * <p>权限判定发生在这里：链接一经签发就带着"已授权"的含义，
 * 之后凭地址即可访问（这正是"短期直链"的定义）。有效期默认 5 分钟。
 */
export function createDownloadLink(publicId: string, docPublicId: string) {
  return post<DownloadLink>(`/v1/workspaces/${publicId}/documents/${docPublicId}/download-link`)
}

/**
 * 读取解析分块，按序号升序。
 *
 * @param publicId    空间对外标识
 * @param docPublicId 文档对外标识
 * @param page        页码，从 1 开始
 * @param size        页大小
 */
export function listChunks(publicId: string, docPublicId: string, page = 1, size = 50) {
  return get<PageResponse<DocumentChunk>>(
    `/v1/workspaces/${publicId}/documents/${docPublicId}/chunks`,
    { query: { page, size } },
  )
}

/**
 * 重新解析。
 *
 * <p>上传者本人，或拥有者/管理员。重复调用是安全的：任务只有一行，
 * 重置而不是新建；正在执行中的任务不会被打断。
 */
export function retryParse(publicId: string, docPublicId: string) {
  return post<DocumentItem>(`/v1/workspaces/${publicId}/documents/${docPublicId}/parse`)
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

/**
 * 解析状态的中文标签。
 *
 * @param status 状态
 */
export function parseStatusLabel(status: ParseStatus): string {
  switch (status) {
    case 'PENDING':
      return '等待解析'
    case 'PROCESSING':
      return '解析中'
    case 'READY':
      return '已就绪'
    case 'FAILED':
      return '解析失败'
  }
}

/**
 * 解析状态对应的标签颜色。取值来自 Arco 的语义色板。
 *
 * @param status 状态
 */
export function parseStatusColor(status: ParseStatus): string {
  switch (status) {
    case 'PENDING':
      return 'gray'
    case 'PROCESSING':
      return 'arcoblue'
    case 'READY':
      return 'green'
    case 'FAILED':
      return 'red'
  }
}

/**
 * 空间角色的中文标签。
 *
 * @param role 角色
 */
export function roleLabel(role: WorkspaceRole): string {
  return role === 'OWNER' ? '拥有者' : role === 'ADMIN' ? '管理员' : '成员'
}

/**
 * 可见性的中文标签。
 *
 * @param visibility 可见性
 */
export function visibilityLabel(visibility: WorkspaceVisibility): string {
  return visibility === 'TEAM' ? '团队空间' : '私有空间'
}

/**
 * 把字节数格式化成人类可读的大小。
 *
 * <p>刻意用 1024 进制并标注 KiB/MiB 而不是 KB/MB：上传上限是按字节数配置的，
 * 而"1 MB"在两种进制下相差 5% —— 在"文件刚好压线"的场景里，
 * 这个差别会让用户觉得"明明没超却被拒了"。
 *
 * @param bytes 字节数
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KiB', 'MiB', 'GiB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`
}
