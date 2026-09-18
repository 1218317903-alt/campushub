<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { ApiError, NetworkError } from '@/api/http'
import {
  createDownloadLink,
  deleteDocument,
  formatBytes,
  listDocuments,
  parseStatusColor,
  parseStatusLabel,
  retryParse,
  uploadDocument,
  type DocumentItem,
} from '@/api/workspace'
import { formatDateTime } from '@/utils/datetime'

/**
 * 空间内的文档面板：上传、解析状态、分块与下载。
 *
 * <h2>上传之后的「等待」必须有可见的进度</h2>
 * 解析由后端的数据库任务队列异步完成，上传接口返回时状态一定是 {@code PENDING}。
 * 如果这一页在拿到响应后就停住不动，用户看到的将是一份永远停在「等待解析」的文档 ——
 * 他无法区分"还没轮到"与"已经坏了"。因此在列表里存在未完成的文档时，
 * 这一页会**有界地轮询**：每 3 秒一次，最多 40 次（约 2 分钟）。
 *
 * <p>用轮询而不是 SSE/WebSocket 是当下的诚实选择：本项目的解析粒度是"整份文档一次"，
 * 一次请求返回的是一份小列表，代价可以忽略；而引入推送通道需要一个本阶段还没有的
 * 基础设施。等到有真实的实时性需求（多端协同编辑之类）时再换，而不是现在先搭一条通道。
 *
 * <p>轮询是**有界**的：超过上限就停下并提示手动刷新。一个永不停止的轮询会在标签页
 * 被遗忘时持续打后端，而它换来的信息量在 2 分钟之后就趋近于零。
 */
const props = defineProps<{ publicId: string }>()

const documents = ref<DocumentItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')
const notice = ref('')

/** 上传 */
const fileInput = ref<HTMLInputElement | null>(null)
const pendingFile = ref<File | null>(null)
const uploading = ref(false)

/** 每个文档当前展示的下载链接（按需签发，不预先取） */
const links = ref<Record<string, { url: string; expiresAt: string }>>({})
/** 正在签发的文档 */
const signing = ref<string | null>(null)

/** 轮询 */
const POLL_INTERVAL_MS = 3_000
const POLL_MAX_ROUNDS = 40
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollRounds = 0

/** 是否存在尚未完成解析的文档 */
const hasUnfinished = computed(() =>
  documents.value.some((item) => item.parseStatus === 'PENDING' || item.parseStatus === 'PROCESSING'),
)

/**
 * 加载文档列表。
 *
 * @param resetPolling 是否重置轮询计数（首次加载与手动刷新时为 true）
 */
async function load(resetPolling = false): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await listDocuments(props.publicId, page.value, pageSize)
    documents.value = data.items
    total.value = data.total
    if (resetPolling) {
      pollRounds = 0
    }
    schedulePoll()
  } catch (error) {
    errorMessage.value = describe(error)
    stopPoll()
  } finally {
    loading.value = false
  }
}

/**
 * 安排下一次轮询。
 *
 * <p>只有"列表里还有未完成的文档"且"还没到轮询上限"时才排。
 */
function schedulePoll(): void {
  stopPoll()
  if (!hasUnfinished.value) {
    pollRounds = 0
    return
  }
  if (pollRounds >= POLL_MAX_ROUNDS) {
    notice.value = '解析仍在进行。已停止自动刷新，可点「刷新」查看最新状态。'
    return
  }
  pollRounds += 1
  pollTimer = setTimeout(() => {
    void load()
  }, POLL_INTERVAL_MS)
}

/** 取消已排定的轮询 */
function stopPoll(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/**
 * 选择文件。
 *
 * <p>这里刻意**不**在前端硬编码大小上限与类型白名单：它们是后端配置
 * （{@code app.workspace.documents.max-size-bytes} 与 {@code allowed-types}），
 * 在前端复制一份出来，就是一份迟早会说谎的副本 —— 配置调大而前端没跟着改的表现，
 * 是"明明允许却被前端拦住"。这里只做一件事：确认用户确实选了一个文件。
 */
function pickFile(): void {
  fileInput.value?.click()
}

/**
 * 记录选中的文件。
 *
 * @param event change 事件
 */
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  pendingFile.value = input.files?.[0] ?? null
  errorMessage.value = ''
  notice.value = ''
}

/**
 * 上传选中的文件。
 */
async function submitUpload(): Promise<void> {
  const file = pendingFile.value
  if (!file) {
    errorMessage.value = '请先选择文件'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  notice.value = ''
  try {
    await uploadDocument(props.publicId, file)
    pendingFile.value = null
    if (fileInput.value) {
      // 清空 input 的值：否则再次选同一个文件不会触发 change，用户会以为"点了没反应"
      fileInput.value.value = ''
    }
    notice.value = `已上传《${file.name}》，解析已入队。支持解析的类型会在这份列表里自动变成「已就绪」。`
    pollRounds = 0
    await load(true)
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    uploading.value = false
  }
}

/**
 * 重新解析一份文档。
 *
 * <p>后端对重复调用是安全的（任务只有一行，重置而非新建），因此这里不做本地去重。
 *
 * @param item 文档
 */
async function retry(item: DocumentItem): Promise<void> {
  errorMessage.value = ''
  notice.value = ''
  try {
    await retryParse(props.publicId, item.publicId)
    notice.value = `《${item.name}》已重新入队。`
    pollRounds = 0
    await load(true)
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

/**
 * 删除一份文档。
 *
 * @param item 文档
 */
async function remove(item: DocumentItem): Promise<void> {
  errorMessage.value = ''
  notice.value = ''
  try {
    await deleteDocument(props.publicId, item.publicId)
    delete links.value[item.publicId]
    notice.value = `已删除《${item.name}》。`
    await load(true)
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

/**
 * 为一份文档签发短期下载链接。
 *
 * <p>链接是**用一次签一次**的：签发这个动作本身就是后端做权限判定的时机，
 * 缓存它只会让一个已经不再有权限的人继续手握一个可用地址。
 *
 * @param item 文档
 */
async function signLink(item: DocumentItem): Promise<void> {
  signing.value = item.publicId
  errorMessage.value = ''
  try {
    const { data } = await createDownloadLink(props.publicId, item.publicId)
    links.value = {
      ...links.value,
      [item.publicId]: { url: data.url, expiresAt: data.expiresAt },
    }
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    signing.value = null
  }
}

/**
 * 打开下载链接。
 *
 * <p>本地存储后端返回的是本应用地址，对象存储后端返回的是预签名直链 ——
 * 两种形状在这里是同一件事：一个可以直接交给浏览器的地址。前端不需要知道当前是哪种后端。
 *
 * @param url 地址
 */
function openLink(url: string): void {
  window.open(url, '_blank', 'noopener')
}

/**
 * 复制链接到剪贴板。
 *
 * <p>剪贴板 API 在非安全上下文（非 https 且非 localhost）里不可用，
 * 因此失败时不做任何隐藏：链接本身就显示在页面上，用户可以手动选中。
 *
 * @param url 地址
 */
async function copyLink(url: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(url)
    notice.value = '链接已复制。'
  } catch {
    notice.value = '当前环境不允许自动复制，请手动选中链接。'
  }
}

/**
 * 翻页。
 *
 * @param next 目标页码
 */
function changePage(next: number): void {
  page.value = next
  pollRounds = 0
  void load(true)
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

onMounted(() => {
  void load(true)
})

onBeforeUnmount(() => {
  stopPoll()
})
</script>

<template>
  <div class="docs">
    <a-alert v-if="errorMessage" type="error" :title="errorMessage" closable @close="errorMessage = ''" />
    <a-alert v-if="notice" type="info" :title="notice" closable @close="notice = ''" />

    <section class="docs__upload">
      <input ref="fileInput" class="docs__file" type="file" @change="onFileChange" />
      <a-button size="small" :disabled="uploading" @click="pickFile">选择文件</a-button>
      <span class="docs__pending">{{ pendingFile ? pendingFile.name : '尚未选择文件' }}</span>
      <a-button size="small" type="primary" :loading="uploading" :disabled="!pendingFile" @click="submitUpload">
        上传
      </a-button>
      <a-button size="small" type="text" :disabled="loading" @click="load(true)">刷新</a-button>
      <span class="docs__hint">
        可直接解析：纯文本 / Markdown / PDF。压缩包、Office 文档与图片可以保存与下载，但当前没有解析器。
      </span>
    </section>

    <a-spin :loading="loading" class="docs__spin">
      <a-empty v-if="documents.length === 0 && !loading" description="这个空间里还没有文档" />

      <div v-else class="docs__list">
        <article v-for="item in documents" :key="item.publicId" class="docs__item">
          <div class="docs__item-main">
            <RouterLink
              class="docs__name"
              :to="{ name: 'document-detail', params: { publicId: props.publicId, docPublicId: item.publicId } }"
            >
              {{ item.name }}
            </RouterLink>
            <div class="docs__meta">
              <a-tag size="small" :color="parseStatusColor(item.parseStatus)">
                {{ parseStatusLabel(item.parseStatus) }}
              </a-tag>
              <span>{{ formatBytes(item.sizeBytes) }}</span>
              <span>{{ item.mimeType }}</span>
              <span v-if="item.parseStatus === 'READY'">
                {{ item.chunkCount }} 块 / {{ item.textLength }} 字
              </span>
              <span>{{ item.uploader.nickname }}</span>
              <span>{{ formatDateTime(item.createdAt) }}</span>
            </div>

            <a-progress
              v-if="item.parseStatus === 'PENDING' || item.parseStatus === 'PROCESSING'"
              class="docs__progress"
              size="small"
              :percent="item.parseProgress"
            />

            <p v-if="item.parseStatus === 'FAILED' && item.parseMessage" class="docs__failure">
              {{ item.parseMessage }}
            </p>

            <!--
              链接按需签发，存在 links 里（键是文档标识）。
              下面这几处用了非空断言：守卫就在同一个元素上，而 TypeScript 无法对
              `links[item.publicId]` 这种**动态下标的元素访问**做收窄 —— 它不会把
              "刚判过非空"这件事保留到子树里。断言在这里是在陈述守卫已经做过的事，
              不是绕过它。
            -->
            <div v-if="links[item.publicId]" class="docs__link">
              <span class="docs__link-url">{{ links[item.publicId]!.url }}</span>
              <a-button size="mini" type="text" @click="openLink(links[item.publicId]!.url)">打开</a-button>
              <a-button size="mini" type="text" @click="copyLink(links[item.publicId]!.url)">复制</a-button>
              <span class="docs__hint">
                有效期至 {{ formatDateTime(links[item.publicId]!.expiresAt) }}
              </span>
            </div>
          </div>

          <div class="docs__actions">
            <RouterLink
              class="docs__link-btn"
              :to="{ name: 'document-detail', params: { publicId: props.publicId, docPublicId: item.publicId } }"
            >
              分块
            </RouterLink>
            <!--
              下载与解析状态无关：原始字节在上传时就已落存储，
              一份"解析失败"的文档照样应该能被下载回来核对。
            -->
            <a-button size="mini" :loading="signing === item.publicId" @click="signLink(item)">
              下载链接
            </a-button>
            <a-button v-if="item.retryableByMe" size="mini" @click="retry(item)">重新解析</a-button>
            <a-popconfirm
              v-if="item.deletableByMe"
              content="删除后文档与已解析的分块一并清除，不可恢复。"
              type="warning"
              ok-text="删除"
              cancel-text="取消"
              @ok="remove(item)"
            >
              <a-button size="mini" status="danger">删除</a-button>
            </a-popconfirm>
          </div>
        </article>

        <a-pagination
          v-if="total > pageSize"
          :total="total"
          :current="page"
          :page-size="pageSize"
          show-total
          @change="changePage"
        />
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.docs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.docs__upload {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.docs__file {
  display: none;
}

.docs__pending {
  color: var(--ch-text-secondary);
  font-size: 13px;
}

.docs__hint {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.docs__spin {
  display: block;
}

.docs__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.docs__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.docs__item-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.docs__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.docs__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.docs__progress {
  max-width: 320px;
}

.docs__failure {
  margin: 0;
  color: var(--ch-danger);
  font-size: 12px;
}

.docs__link {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.docs__link-url {
  max-width: 420px;
  overflow: hidden;
  color: var(--ch-text-secondary);
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.docs__actions {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.docs__link-btn {
  padding: 2px 8px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  color: var(--ch-text-secondary);
  font-size: 12px;
  text-decoration: none;
}

.docs__link-btn:hover {
  border-color: var(--ch-brand);
  color: var(--ch-brand);
}
</style>
