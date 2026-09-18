<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import {
  deletePost,
  favoritePost,
  fetchPost,
  likePost,
  unfavoritePost,
  unlikePost,
  type PostDetail,
} from '@/api/community'
import { ApiError, NetworkError } from '@/api/http'
import CommentThread from '@/components/CommentThread.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/datetime'

/**
 * 帖子详情。
 *
 * <h2>正文用 v-html 渲染，这是全仓库唯一允许的位置</h2>
 * {@code bodyHtml} 是<b>服务端</b>用 commonmark 渲染并跑过 OWASP 白名单净化的结果，
 * 它已经不含脚本、事件属性与危险协议（对应断言见 {@code MarkdownRendererTest} 与
 * {@code CommunityContentIT}）。因此这里可以直接插入。
 *
 * <p><b>但前端绝不自己渲染用户提交的 Markdown。</b>一旦前端也做一遍渲染，
 * 就等于多出一处必须记得净化的地方 —— 而"每个客户端都记得净化"这件事在
 * 多端之后必然失效。前端在这里的职责只是展示服务端的产物，以及提供
 * "查看原文"的纯文本视图（走文本节点，不走 v-html）。
 *
 * <h2>点赞与收藏以服务端返回的计数为准</h2>
 * 不做本地 ±1：服务端返回的 {@code count} 是权威值。本地自增在并发点赞下会漂移，
 * 而漂移之后没有任何机制能把它纠正回来，只能靠用户刷新页面。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const post = ref<PostDetail | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const notFound = ref(false)

/** 是否显示 Markdown 原文 */
const showSource = ref(false)
/** 正在进行中的互动请求，用于禁用按钮避免重复提交 */
const reacting = ref(false)

const publicId = computed(() => String(route.params.publicId ?? ''))

/**
 * 加载详情。
 */
async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  notFound.value = false
  try {
    const { data } = await fetchPost(publicId.value)
    post.value = data
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound.value = true
    } else if (error instanceof ApiError) {
      errorMessage.value = error.message
    } else if (error instanceof NetworkError) {
      errorMessage.value = `${error.message}。请确认后端已启动（默认 127.0.0.1:8080）。`
    } else {
      errorMessage.value = '发生未知错误'
    }
  } finally {
    loading.value = false
  }
}

/**
 * 点赞 / 取消点赞。
 */
async function toggleLike(): Promise<void> {
  if (!post.value) {
    return
  }
  reacting.value = true
  errorMessage.value = ''
  try {
    const action = post.value.liked ? unlikePost : likePost
    const { data } = await action(post.value.publicId)
    post.value = { ...post.value, liked: data.active, likeCount: data.count }
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    reacting.value = false
  }
}

/**
 * 收藏 / 取消收藏。
 */
async function toggleFavorite(): Promise<void> {
  if (!post.value) {
    return
  }
  reacting.value = true
  errorMessage.value = ''
  try {
    const action = post.value.favorited ? unfavoritePost : favoritePost
    const { data } = await action(post.value.publicId)
    post.value = { ...post.value, favorited: data.active, favoriteCount: data.count }
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    reacting.value = false
  }
}

/**
 * 删除帖子。
 */
async function remove(): Promise<void> {
  if (!post.value) {
    return
  }
  try {
    await deletePost(post.value.publicId)
    void router.push({ name: 'community' })
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

/**
 * 把异常翻译成提示文案。
 *
 * @param error 异常
 * @returns 文案
 */
function describe(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof NetworkError) {
    return `${error.message}。请确认后端已启动。`
  }
  return '发生未知错误'
}

/**
 * 需要登录才能做的动作：未登录时先去登录页，并带上返回地址。
 *
 * @param action 动作
 */
function requireLogin(action: () => void): void {
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  action()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="detail">
    <a-spin :loading="loading" class="detail__spin">
      <a-result
        v-if="notFound"
        status="404"
        title="帖子不存在或已被删除"
        subtitle="它可能已被作者删除，或者链接有误。"
      >
        <template #extra>
          <a-button type="primary" @click="router.push({ name: 'community' })">回到社区首页</a-button>
        </template>
      </a-result>

      <template v-else-if="post">
        <a-alert
          v-if="errorMessage"
          class="detail__alert"
          type="error"
          :title="errorMessage"
          closable
          @close="errorMessage = ''"
        />

        <article class="detail__card">
          <header class="detail__head">
            <h1 class="detail__title">{{ post.title }}</h1>
            <div class="detail__meta">
              <RouterLink class="detail__category" :to="{ name: 'community', query: { category: post.categorySlug } }">
                {{ post.categoryName }}
              </RouterLink>
              <span>{{ post.author.nickname }}</span>
              <span>发布于 {{ formatDateTime(post.publishedAt) }}</span>
              <span v-if="post.updatedAt !== post.publishedAt">
                更新于 {{ formatDateTime(post.updatedAt) }}
              </span>
              <RouterLink
                v-for="tag in post.tags"
                :key="tag.slug"
                class="detail__tag"
                :to="{ name: 'community', query: { tag: tag.slug } }"
              >
                #{{ tag.name }}
              </RouterLink>
            </div>
          </header>

          <!-- 服务端已渲染并净化的 HTML。见组件顶部注释：这是全仓库唯一使用 v-html 的位置 -->
          <div class="detail__body markdown-body" v-html="post.bodyHtml" />

          <div v-if="showSource" class="detail__source">
            <div class="detail__source-head">Markdown 原文</div>
            <pre class="detail__source-body">{{ post.bodyMd }}</pre>
          </div>

          <footer class="detail__actions">
            <a-button
              size="small"
              :type="post.liked ? 'primary' : 'outline'"
              :disabled="reacting"
              @click="requireLogin(toggleLike)"
            >
              👍 赞同 {{ post.likeCount }}
            </a-button>
            <a-button
              size="small"
              :type="post.favorited ? 'primary' : 'outline'"
              :disabled="reacting"
              @click="requireLogin(toggleFavorite)"
            >
              ⭐ 收藏 {{ post.favoriteCount }}
            </a-button>
            <a-button size="small" type="text" @click="showSource = !showSource">
              {{ showSource ? '隐藏原文' : '查看原文' }}
            </a-button>
            <span class="detail__stats">
              👁 {{ post.viewCount }} · 💬 {{ post.commentCount }}
            </span>
            <span class="detail__spacer" />
            <template v-if="post.ownedByMe">
              <a-button size="small" @click="router.push({ name: 'post-edit', params: { publicId: post.publicId } })">
                编辑
              </a-button>
              <a-popconfirm
                content="删除后不可恢复，正文与评论都不再对外可见。"
                type="warning"
                ok-text="删除"
                cancel-text="取消"
                @ok="remove"
              >
                <a-button size="small" status="danger">删除</a-button>
              </a-popconfirm>
            </template>
          </footer>
        </article>

        <CommentThread :post-id="post.publicId" />
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail__spin {
  display: block;
}

.detail__alert {
  margin-bottom: 16px;
}

.detail__card {
  padding: 24px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.detail__title {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.4;
}

.detail__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ch-border);
  color: var(--ch-text-tertiary);
  font-size: 13px;
}

.detail__category {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--ch-brand-weak);
  color: var(--ch-brand);
  text-decoration: none;
  font-size: 12px;
}

.detail__tag {
  color: var(--ch-text-tertiary);
  text-decoration: none;
  font-size: 12px;
}

.detail__tag:hover {
  color: var(--ch-brand);
}

.detail__body {
  padding: 18px 0;
}

.detail__source {
  margin-bottom: 16px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  overflow: hidden;
}

.detail__source-head {
  padding: 6px 12px;
  border-bottom: 1px solid var(--ch-border);
  background: var(--ch-bg-page);
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.detail__source-body {
  margin: 0;
  padding: 12px;
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ch-text-secondary);
}

.detail__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--ch-border);
}

.detail__stats {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.detail__spacer {
  flex: 1;
}
</style>
