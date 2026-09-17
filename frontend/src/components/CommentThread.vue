<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import {
  createComment,
  deleteComment,
  fetchComments,
  fetchReplies,
  type CommentItem,
} from '@/api/community'
import { ApiError, NetworkError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/datetime'

/**
 * 评论区。
 *
 * <h2>两级结构，回复按需加载</h2>
 * 服务端的数据模型是严格两层：顶层评论 + 回复（回复不能有回复）。
 * 界面上因此不做无限缩进的树 —— 那会让"本平台是两层"这个事实在 UI 上变得含糊，
 * 用户会以为自己可以回复某一条回复，然后在提交时收到 40001。
 *
 * <p>回复默认折叠。一条有 20 条回复的评论会把整个页面撑开，
 * 而绝大多数访客只想先看顶层讨论。
 *
 * <h2>删除的连带效果必须提前说</h2>
 * 删除顶层评论会连带删除其下的全部回复（服务端行为）。这件事写在确认文案里，
 * 而不是删完之后再解释为什么别人的回复也没了。
 */
const props = defineProps<{
  postId: string
}>()

const auth = useAuthStore()

const comments = ref<CommentItem[]>([])
const loading = ref(false)
const errorMessage = ref('')

const total = ref(0)
const page = ref(1)
const size = 20
const hasNext = ref(false)

/** 顶层评论正文输入框的内容 */
const draft = ref('')
const submitting = ref(false)

/** 展开的回复：评论 publicId → 回复列表 */
const replies = ref<Record<string, CommentItem[]>>({})
const repliesLoading = ref<Record<string, boolean>>({})

/** 正在回复哪条评论（null 表示没有） */
const replyingTo = ref<string | null>(null)
const replyDraft = ref('')

/**
 * 把异常翻译成一句用户能看懂的话。
 *
 * @param error 捕获到的异常
 * @returns 提示文案
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
 * 加载顶层评论（第一页重新加载 / 追加下一页）。
 *
 * @param next 是否加载下一页
 */
async function load(next = false): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const targetPage = next ? page.value + 1 : 1
    const { data } = await fetchComments(props.postId, targetPage, size)
    comments.value = next ? [...comments.value, ...data.items] : data.items
    page.value = data.page
    total.value = data.total
    hasNext.value = data.hasNext
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    loading.value = false
  }
}

/**
 * 展开 / 收起某条评论的回复。
 *
 * @param comment 顶层评论
 */
async function toggleReplies(comment: CommentItem): Promise<void> {
  if (replies.value[comment.publicId]) {
    const next = { ...replies.value }
    delete next[comment.publicId]
    replies.value = next
    return
  }

  repliesLoading.value = { ...repliesLoading.value, [comment.publicId]: true }
  try {
    const { data } = await fetchReplies(comment.publicId, 1, 20)
    replies.value = { ...replies.value, [comment.publicId]: data.items }
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    repliesLoading.value = { ...repliesLoading.value, [comment.publicId]: false }
  }
}

/**
 * 发表顶层评论。
 */
async function submitComment(): Promise<void> {
  const text = draft.value.trim()
  if (!text) {
    errorMessage.value = '评论内容不能为空'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const { data } = await createComment(props.postId, text)
    comments.value = [data, ...comments.value]
    total.value += 1
    draft.value = ''
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 发表回复。
 *
 * @param parent 被回复的顶层评论
 */
async function submitReply(parent: CommentItem): Promise<void> {
  const text = replyDraft.value.trim()
  if (!text) {
    errorMessage.value = '回复内容不能为空'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const { data } = await createComment(props.postId, text, parent.publicId)
    const existing = replies.value[parent.publicId] ?? []
    replies.value = { ...replies.value, [parent.publicId]: [...existing, data] }
    // 回复数由前端同步加 1；服务端的计数在下次刷新时校准。
    // 不重新拉取整个列表：那会让"刚发的那条回复"闪一下才出现
    parent.replyCount += 1
    replyDraft.value = ''
    replyingTo.value = null
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 删除评论。
 *
 * @param comment 要删除的评论
 * @param parentId 它所属的顶层评论；删除回复时传入
 */
async function remove(comment: CommentItem, parentId?: string): Promise<void> {
  errorMessage.value = ''
  try {
    await deleteComment(comment.publicId)
    if (parentId) {
      replies.value = {
        ...replies.value,
        [parentId]: (replies.value[parentId] ?? []).filter((it) => it.publicId !== comment.publicId),
      }
    } else {
      comments.value = comments.value.filter((it) => it.publicId !== comment.publicId)
    }
    total.value = Math.max(total.value - 1, 0)
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="comments">
    <header class="comments__head">
      <h2 class="comments__title">评论 <span class="comments__count">{{ total }}</span></h2>
    </header>

    <a-alert
      v-if="errorMessage"
      class="comments__alert"
      type="error"
      :title="errorMessage"
      closable
      @close="errorMessage = ''"
    />

    <div v-if="auth.isLoggedIn" class="comments__composer">
      <a-textarea
        v-model="draft"
        placeholder="说点什么…（纯文本，不支持 Markdown）"
        :auto-size="{ minRows: 2, maxRows: 6 }"
        :max-length="1000"
        show-word-limit
      />
      <div class="comments__composer-actions">
        <a-button type="primary" size="small" :loading="submitting" @click="submitComment">
          发表评论
        </a-button>
      </div>
    </div>

    <div v-else class="comments__guest">
      <span>登录后可以参与讨论。</span>
      <RouterLink class="comments__login-link" to="/login">去登录</RouterLink>
    </div>

    <a-spin :loading="loading" class="comments__spin">
      <a-empty v-if="comments.length === 0 && !loading" description="还没有评论，来说第一句" />

      <ul v-else class="comments__list">
        <li v-for="comment in comments" :key="comment.publicId" class="comments__item">
          <div class="comments__item-head">
            <span class="comments__author">{{ comment.author.nickname }}</span>
            <span class="comments__time">{{ formatDateTime(comment.createdAt) }}</span>
            <a-popconfirm
              v-if="comment.ownedByMe"
              content="删除后不可恢复。若这是顶层评论，它下面的回复也会一并删除。"
              type="warning"
              ok-text="删除"
              cancel-text="取消"
              @ok="remove(comment)"
            >
              <button class="comments__action" type="button">删除</button>
            </a-popconfirm>
          </div>

          <p class="comments__body">{{ comment.body }}</p>

          <div class="comments__item-actions">
            <button
              v-if="auth.isLoggedIn"
              class="comments__action"
              type="button"
              @click="replyingTo = replyingTo === comment.publicId ? null : comment.publicId"
            >
              {{ replyingTo === comment.publicId ? '取消回复' : '回复' }}
            </button>
            <button
              v-if="comment.replyCount > 0 || replies[comment.publicId]"
              class="comments__action"
              type="button"
              @click="toggleReplies(comment)"
            >
              {{ replies[comment.publicId] ? '收起回复' : `查看 ${comment.replyCount} 条回复` }}
            </button>
          </div>

          <div v-if="replyingTo === comment.publicId" class="comments__reply-composer">
            <a-textarea
              v-model="replyDraft"
              :placeholder="`回复 ${comment.author.nickname}…`"
              :auto-size="{ minRows: 2, maxRows: 4 }"
              :max-length="1000"
              show-word-limit
            />
            <div class="comments__composer-actions">
              <a-button size="small" :loading="submitting" @click="submitReply(comment)">
                发表回复
              </a-button>
            </div>
          </div>

          <a-spin v-if="repliesLoading[comment.publicId]" class="comments__spin" />

          <ul v-if="replies[comment.publicId]" class="comments__replies">
            <li v-for="reply in replies[comment.publicId]" :key="reply.publicId" class="comments__reply">
              <div class="comments__item-head">
                <span class="comments__author">{{ reply.author.nickname }}</span>
                <span class="comments__time">{{ formatDateTime(reply.createdAt) }}</span>
                <a-popconfirm
                  v-if="reply.ownedByMe"
                  content="删除后不可恢复。"
                  type="warning"
                  ok-text="删除"
                  cancel-text="取消"
                  @ok="remove(reply, comment.publicId)"
                >
                  <button class="comments__action" type="button">删除</button>
                </a-popconfirm>
              </div>
              <p class="comments__body">{{ reply.body }}</p>
            </li>
          </ul>
        </li>
      </ul>

      <div v-if="hasNext" class="comments__more">
        <a-button size="small" :loading="loading" @click="load(true)">加载更多评论</a-button>
      </div>
    </a-spin>
  </section>
</template>

<style scoped>
.comments {
  padding: 20px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.comments__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.comments__count {
  margin-left: 4px;
  color: var(--ch-text-tertiary);
  font-weight: 400;
}

.comments__alert {
  margin-top: 12px;
}

.comments__composer {
  margin-top: 14px;
}

.comments__composer-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.comments__guest {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: var(--ch-radius);
  background: var(--ch-bg-page);
  color: var(--ch-text-secondary);
  font-size: 13px;
}

.comments__login-link {
  color: var(--ch-brand);
}

.comments__spin {
  display: block;
  margin-top: 14px;
}

.comments__list,
.comments__replies {
  margin: 0;
  padding: 0;
  list-style: none;
}

.comments__item + .comments__item {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--ch-border);
}

.comments__item-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.comments__author {
  color: var(--ch-text-primary);
  font-weight: 500;
}

.comments__time {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.comments__body {
  margin: 6px 0 6px;
  color: var(--ch-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.comments__item-actions {
  display: flex;
  gap: 12px;
}

.comments__action {
  padding: 0;
  border: none;
  background: none;
  color: var(--ch-text-tertiary);
  cursor: pointer;
  font-size: 12px;
}

.comments__action:hover {
  color: var(--ch-brand);
}

.comments__reply-composer {
  margin-top: 10px;
}

.comments__replies {
  margin-top: 12px;
  padding: 12px;
  border-radius: var(--ch-radius);
  background: var(--ch-bg-page);
}

.comments__reply + .comments__reply {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--ch-border);
}

.comments__more {
  margin-top: 16px;
  text-align: center;
}
</style>
