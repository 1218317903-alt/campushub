import { del, get, post, put } from './http'

/**
 * 社区接口。
 *
 * <h2>浏览公开、写入需要登录</h2>
 * 列表、详情、评论读取都不需要令牌；发帖、评论、点赞、收藏都需要。
 * 带着令牌浏览时，服务端会额外把 {@code liked} / {@code favorited} / {@code ownedByMe}
 * 填成真实值 —— 这三个字段在匿名时恒为 false，但**始终存在**，
 * 因此前端不需要根据登录态写两套渲染分支。
 */

/** 作者信息。账号已注销时 {@code publicId} 与 {@code avatarUrl} 为 null。 */
export interface Author {
  publicId: string | null
  nickname: string
  avatarUrl: string | null
}

/** 标签引用。 */
export interface TagRef {
  slug: string
  name: string
}

/** 板块。 */
export interface Category {
  slug: string
  name: string
  description: string | null
  postCount: number
}

/** 标签（含被引用的帖子数）。 */
export interface Tag {
  slug: string
  name: string
  postCount: number
}

/** 分页响应。字段与后端 `PageResponse` 一一对应。 */
export interface PageResponse<T> {
  items: T[]
  /** 从 1 开始。后端已归一化，前端不需要再判断 0 */
  page: number
  /** 实际生效的页大小，可能小于请求值（服务端有上限） */
  size: number
  total: number
  hasNext: boolean
}

/** 列表卡片。**不含正文** —— 列表页拿到的是摘要。 */
export interface PostCard {
  publicId: string
  title: string
  summary: string
  categorySlug: string
  categoryName: string
  author: Author
  tags: TagRef[]
  viewCount: number
  likeCount: number
  favoriteCount: number
  commentCount: number
  publishedAt: string
  liked: boolean
  favorited: boolean
  ownedByMe: boolean
}

/** 帖子详情。比列表卡片多出正文与更新时间。 */
export interface PostDetail extends PostCard {
  /** 服务端渲染并净化后的 HTML。前端直接插入，**不做二次渲染或转义** */
  bodyHtml: string
  /** Markdown 原文，供"查看原文"与编辑预填使用 */
  bodyMd: string
  updatedAt: string
}

/** 评论。 */
export interface CommentItem {
  publicId: string
  author: Author
  /** 纯文本（保留换行）。刻意不渲染 Markdown —— 见后端 V3 迁移的说明 */
  body: string
  /** 顶层评论的回复数；回复自身的该值恒为 0 */
  replyCount: number
  createdAt: string
  ownedByMe: boolean
}

/** 点赞 / 收藏操作后的状态。 */
export interface ReactionResult {
  /** 操作完成后"我是否处于该状态" */
  active: boolean
  /** 操作完成后的计数 */
  count: number
}

/** 帖子排序方式。取值会被服务端忽略大小写。 */
export type PostSort = 'latest' | 'hot'

/**
 * 列表查询条件。
 *
 * <p>每个字段都显式带上 {@code | undefined}：项目开了
 * {@code exactOptionalPropertyTypes}，而调用方习惯写「有值才传」
 * （{@code category: slug || undefined}）。不给 {@code undefined} 的话，
 * 每个调用处都要多写一次条件展开，而这只是为了让类型系统高兴。
 *
 * <p>{@code http} 层会跳过值为 {@code undefined} 的查询键，
 * 因此显式的 {@code undefined} 不会被拼进 URL。
 */
export interface FeedQuery {
  category?: string | undefined
  tag?: string | undefined
  sort?: PostSort | undefined
  page?: number | undefined
  size?: number | undefined
}

/**
 * 拉取板块列表。
 *
 * @returns 板块列表（按展示顺序）
 */
export function fetchCategories() {
  return get<Category[]>('/v1/community/categories')
}

/**
 * 拉取热门标签。
 *
 * @returns 按被引用帖子数倒序的标签，最多 20 个
 */
export function fetchTags() {
  return get<Tag[]>('/v1/community/tags')
}

/**
 * 分页查询帖子列表。
 *
 * @param query 查询条件；空字符串的筛选项由服务端当作"未指定"
 * @returns 分页结果
 */
export function fetchPosts(query: FeedQuery = {}) {
  return get<PageResponse<PostCard>>('/v1/community/posts', { query: { ...query } })
}

/**
 * 查询帖子详情。
 *
 * <p>带令牌访问会记录一次浏览（同一用户同一天只计一次），匿名不计。
 *
 * @param publicId 帖子对外标识
 * @returns 详情
 */
export function fetchPost(publicId: string) {
  return get<PostDetail>(`/v1/community/posts/${publicId}`)
}

/** 发布或编辑帖子的请求体。 */
export interface PostPayload {
  categorySlug: string
  title: string
  /** Markdown 正文 */
  bodyMd: string
  tagNames: string[]
}

/**
 * 发布帖子。
 *
 * @param payload 内容
 * @returns 新帖详情
 */
export function createPost(payload: PostPayload) {
  return post<PostDetail>('/v1/community/posts', payload)
}

/**
 * 编辑帖子（仅作者本人）。
 *
 * @param publicId 帖子对外标识
 * @param payload  新内容
 * @returns 更新后的详情
 */
export function updatePost(publicId: string, payload: PostPayload) {
  return put<PostDetail>(`/v1/community/posts/${publicId}`, payload)
}

/**
 * 删除帖子（软删除，仅作者本人）。
 *
 * @param publicId 帖子对外标识
 */
export function deletePost(publicId: string) {
  return del<void>(`/v1/community/posts/${publicId}`)
}

/**
 * 查询帖子的顶层评论。
 *
 * @param publicId 帖子对外标识
 * @param page     页码
 * @param size     页大小
 * @returns 分页结果
 */
export function fetchComments(publicId: string, page = 1, size = 20) {
  return get<PageResponse<CommentItem>>(`/v1/community/posts/${publicId}/comments`, {
    query: { page, size },
  })
}

/**
 * 发表评论或回复。
 *
 * @param publicId 帖子对外标识
 * @param body     评论正文
 * @param parentId 父评论对外标识；发表顶层评论时省略
 * @returns 新评论
 */
export function createComment(publicId: string, body: string, parentId?: string) {
  return post<CommentItem>(`/v1/community/posts/${publicId}/comments`, {
    body,
    ...(parentId ? { parentId } : {}),
  })
}

/**
 * 查询某条顶层评论的回复（按时间正序）。
 *
 * @param commentPublicId 顶层评论对外标识
 * @param page            页码
 * @param size            页大小
 * @returns 分页结果
 */
export function fetchReplies(commentPublicId: string, page = 1, size = 20) {
  return get<PageResponse<CommentItem>>(`/v1/community/comments/${commentPublicId}/replies`, {
    query: { page, size },
  })
}

/**
 * 删除评论（软删除，仅作者本人；删顶层评论会连带删除其下全部回复）。
 *
 * @param commentPublicId 评论对外标识
 */
export function deleteComment(commentPublicId: string) {
  return del<void>(`/v1/community/comments/${commentPublicId}`)
}

/**
 * 点赞（幂等）。
 *
 * @param publicId 帖子对外标识
 * @returns 操作后的状态与计数
 */
export function likePost(publicId: string) {
  return post<ReactionResult>(`/v1/community/posts/${publicId}/like`)
}

/**
 * 取消点赞（幂等）。
 *
 * @param publicId 帖子对外标识
 * @returns 操作后的状态与计数
 */
export function unlikePost(publicId: string) {
  return del<ReactionResult>(`/v1/community/posts/${publicId}/like`)
}

/**
 * 收藏（幂等）。
 *
 * @param publicId 帖子对外标识
 * @returns 操作后的状态与计数
 */
export function favoritePost(publicId: string) {
  return post<ReactionResult>(`/v1/community/posts/${publicId}/favorite`)
}

/**
 * 取消收藏（幂等）。
 *
 * @param publicId 帖子对外标识
 * @returns 操作后的状态与计数
 */
export function unfavoritePost(publicId: string) {
  return del<ReactionResult>(`/v1/community/posts/${publicId}/favorite`)
}

/**
 * 查询我收藏的帖子。
 *
 * @param page 页码
 * @param size 页大小
 * @returns 分页结果
 */
export function fetchMyFavorites(page = 1, size = 20) {
  return get<PageResponse<PostCard>>('/v1/community/me/favorites', { query: { page, size } })
}
