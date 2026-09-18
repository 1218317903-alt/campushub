<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, NetworkError } from '@/api/http'
import {
  createDownloadLink,
  deleteDocument,
  formatBytes,
  getDocument,
  listChunks,
  parseStatusColor,
  parseStatusLabel,
  retryParse,
  type DocumentChunk,
  type DocumentItem,
} from '@/api/workspace'
import { formatDateTime } from '@/utils/datetime'

/**
 * 文档详情：解析状态、分块与下载。
 *
 * <h2>这一页存在的理由</h2>
 * 「上传之后发生了什么」在只给一个状态标签时是不可验证的：用户只能选择相信。
 * 把解析结果按块摊开（序号、标题路径、块大小、正文）之后，解析对不对是**用户能自己看出来的**：
 * 一份 Markdown 被切成 3 块还是被合并成 1 块、标题路径有没有丢、有没有出现乱码。
 * 这也是当前阶段没有向量检索时，chunk 表唯一能被真实使用的形态。
 *
 * <h2>为什么在这里轮询而不是让用户手动刷新</h2>
 * 解析耗时从几百毫秒到数秒不等，取决于文件大小。用户在提交上传后的第一反应就是打开这一页，
 * 而他此刻看到的必然是 {@code PENDING}。轮询是有界的（每 3 秒一次、最多 40 次，约 2 分钟），
 * 到点即停并提示手动刷新 —— 见 {@code WorkspaceDocumentPanel} 里同样的取舍说明。
 */
const route = useRoute()
const router = useRouter()

const workspacePublicId = computed(() => String(route.params.publicId ?? ''))
const docPublicId = computed(() => String(route.params.docPublicId ?? ''))

const doc = ref<DocumentItem | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const notFound = ref(false)
const notice = ref('')

/** 分块 */
const chunks = ref<DocumentChunk[]>([])
const chunkTotal = ref(0)
const chunkPage = ref(1)
const chunkPageSize = 20
const chunkLoading = ref(false)
const chunkError = ref('')

/** 下载链接（按需签发） */
const link = ref<{ url: string; expiresAt: string } | null>(null)
const signing = ref(false)

/** 重试与删除进行中 */
const retrying = ref(false)

/** 轮询 */
const POLL_INTERVAL_MS = 3_000
const POLL_MAX_ROUNDS = 40
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollRounds = 0

/** 是否还在等待解析结果 */
const unfinished = computed(
  () => doc.value !== null && (doc.value.parseStatus === 'PENDING' || doc.value.parseStatus === 'PROCESSING'),
)

/**
 * 加载文档元数据。
 *
 * @param resetPolling 是否重置轮询计数
 */
async function load(resetPolling = false): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await getDocument(workspacePublicId.value, docPublicId.value)
    const was = doc.value?.parseStatus
    doc.value = data
    notFound.value = false
    if (resetPolling) {
      pollRounds = 0
    }
    // 状态刚刚收敛到 READY 时把分块读上来：这是用户打开这一页想看的东西
    if (data.parseStatus === 'READY' && was !== 'READY') {
      await loadChunks(1)
    }
    schedulePoll()
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound.value = true
      stopPoll()
    } else {
      errorMessage.value = describe(error)
      stopPoll()
    }
  } finally {
    loading.value = false
  }
}

/**
 * 加载分块。
 *
 * @param nextPage 目标页码
 */
async function loadChunks(nextPage: number): Promise<void> {
  chunkPage.value = nextPage
  chunkLoading.value = true
  chunkError.value = ''
  try {
    const { data } = await listChunks(workspacePublicId.value, docPublicId.value, nextPage, chunkPageSize)
    chunks.value = data.items
    chunkTotal.value = data.total
  } catch (error) {
    chunkError.value = describe(error)
  } finally {
    chunkLoading.value = false
  }
}

/**
 * 安排下一次轮询；未完成且未到上限时才排。
 */
function schedulePoll(): void {
  stopPoll()
  if (!unfinished.value) {
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
 * 重新解析。
 */
async function retry(): Promise<void> {
  retrying.value = true
  errorMessage.value = ''
  notice.value = ''
  try {
    await retryParse(workspacePublicId.value, docPublicId.value)
    notice.value = '已重新入队，稍候会自动刷新状态。'
    chunks.value = []
    chunkTotal.value = 0
    pollRounds = 0
    await load(true)
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    retrying.value = false
  }
}

/**
 * 签发下载链接。
 */
async function signLink(): Promise<void> {
  signing.value = true
  errorMessage.value = ''
  try {
    const { data } = await createDownloadLink(workspacePublicId.value, docPublicId.value)
    link.value = { url: data.url, expiresAt: data.expiresAt }
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    signing.value = false
  }
}

/**
 * 打开链接。
 *
 * @param url 地址
 */
function openLink(url: string): void {
  window.open(url, '_blank', 'noopener')
}

/**
 * 复制链接。
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
 * 删除这份文档，成功后回到空间。
 */
async function remove(): Promise<void> {
  try {
    await deleteDocument(workspacePublicId.value, docPublicId.value)
    void router.push({ name: 'workspace-detail', params: { publicId: workspacePublicId.value } })
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

onMounted(() => {
  void load(true)
})

onBeforeUnmount(() => {
  stopPoll()
})
</script>

<template>
  <div class="doc">
    <a-spin :loading="loading" class="doc__spin">
      <a-result
        v-if="notFound"
        status="404"
        title="文档不存在或你没有访问权限"
        subtitle="它可能已被删除，也可能属于别的空间 —— 这两种情况在响应上不作区分。"
      >
        <template #extra>
          <a-button type="primary" @click="router.push({ name: 'spaces' })">回到我的空间</a-button>
        </template>
      </a-result>

      <template v-else-if="doc">
        <a-alert v-if="errorMessage" type="error" :title="errorMessage" closable @close="errorMessage = ''" />
        <a-alert v-if="notice" type="info" :title="notice" closable @close="notice = ''" />

        <header class="doc__head">
          <div class="doc__head-main">
            <h1 class="doc__title">{{ doc.name }}</h1>
            <div class="doc__meta">
              <a-tag size="small" :color="parseStatusColor(doc.parseStatus)">
                {{ parseStatusLabel(doc.parseStatus) }}
              </a-tag>
              <span>{{ doc.mimeType }}</span>
              <span>{{ formatBytes(doc.sizeBytes) }}</span>
              <span>{{ doc.uploader.nickname }} 上传于 {{ formatDateTime(doc.createdAt) }}</span>
            </div>
          </div>
          <a-space wrap>
            <a-button size="small" @click="router.push({ name: 'workspace-detail', params: { publicId: workspacePublicId } })">
              返回空间
            </a-button>
            <a-button size="small" :loading="signing" @click="signLink">获取下载链接</a-button>
            <a-button v-if="doc.retryableByMe" size="small" :loading="retrying" @click="retry">
              重新解析
            </a-button>
            <a-popconfirm
              v-if="doc.deletableByMe"
              content="删除后文档与已解析的分块一并清除，不可恢复。"
              type="warning"
              ok-text="删除"
              cancel-text="取消"
              @ok="remove"
            >
              <a-button size="small" status="danger">删除</a-button>
            </a-popconfirm>
          </a-space>
        </header>

        <div v-if="link" class="doc__link">
          <span class="doc__link-url">{{ link.url }}</span>
          <a-button size="mini" type="text" @click="openLink(link.url)">打开</a-button>
          <a-button size="mini" type="text" @click="copyLink(link.url)">复制</a-button>
          <span class="doc__hint">有效期至 {{ formatDateTime(link.expiresAt) }}</span>
        </div>

        <!--
          Arco 的 Alert 只有 title 这一个属性，说明文字走默认插槽。
          失败原因是**面向用户**的文案（后端已过滤掉堆栈与内部类名），因此可以直接展示。
        -->
        <a-alert v-if="doc.parseStatus === 'FAILED'" type="error" :title="doc.parseMessage || '解析失败'">
          {{
            doc.retryableByMe
              ? '可以点「重新解析」再试一次；若同一种文件反复失败，多半是内容本身读不出来。'
              : '请联系上传者或空间拥有者处理。'
          }}
        </a-alert>

        <a-progress
          v-if="doc.parseStatus === 'PENDING' || doc.parseStatus === 'PROCESSING'"
          class="doc__progress"
          :percent="doc.parseProgress"
        />

        <a-card class="doc__card" title="解析结果">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="状态">{{ parseStatusLabel(doc.parseStatus) }}</a-descriptions-item>
            <a-descriptions-item label="进度">{{ doc.parseProgress }}%</a-descriptions-item>
            <a-descriptions-item label="分块数">{{ doc.chunkCount }}</a-descriptions-item>
            <a-descriptions-item label="文本长度">{{ doc.textLength }} 字</a-descriptions-item>
            <a-descriptions-item label="解析时间">
              {{ doc.parsedAt ? formatDateTime(doc.parsedAt) : '—' }}
            </a-descriptions-item>
            <a-descriptions-item label="最后更新">{{ formatDateTime(doc.updatedAt) }}</a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card class="doc__card" title="分块">
          <template #extra>
            <a-button
              v-if="doc.parseStatus === 'READY'"
              size="mini"
              type="text"
              :disabled="chunkLoading"
              @click="loadChunks(chunkPage)"
            >
              刷新
            </a-button>
          </template>

          <a-empty
            v-if="doc.parseStatus !== 'READY'"
            :description="
              doc.parseStatus === 'FAILED'
                ? '解析失败，没有可查看的分块。'
                : '解析完成后这里会显示切分结果。'
            "
          />
          <template v-else>
            <a-alert v-if="chunkError" type="error" :title="chunkError" closable @close="chunkError = ''" />
            <a-spin :loading="chunkLoading" class="doc__spin">
              <a-empty v-if="chunks.length === 0 && !chunkLoading" description="这份文档还没有分块" />
              <div v-else class="doc__chunks">
                <article v-for="chunk in chunks" :key="chunk.ordinal" class="doc__chunk">
                  <div class="doc__chunk-head">
                    <span class="doc__chunk-ordinal">#{{ chunk.ordinal }}</span>
                    <span class="doc__chunk-heading">{{ chunk.heading || '（无标题）' }}</span>
                    <span class="doc__chunk-size">{{ chunk.charCount }} 字</span>
                  </div>
                  <pre class="doc__chunk-body">{{ chunk.content }}</pre>
                </article>

                <a-pagination
                  v-if="chunkTotal > chunkPageSize"
                  :total="chunkTotal"
                  :current="chunkPage"
                  :page-size="chunkPageSize"
                  show-total
                  @change="loadChunks"
                />
              </div>
            </a-spin>
          </template>
        </a-card>
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.doc {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.doc__spin {
  display: block;
}

.doc__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.doc__head-main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.doc__title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  word-break: break-all;
}

.doc__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.doc__link {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px dashed var(--ch-border);
  border-radius: var(--ch-radius);
}

.doc__link-url {
  max-width: 480px;
  overflow: hidden;
  color: var(--ch-text-secondary);
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc__hint {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.doc__progress {
  max-width: 420px;
}

.doc__card {
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
}

.doc__chunks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.doc__chunk {
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  overflow: hidden;
}

.doc__chunk-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--ch-border);
  background: var(--ch-bg-page);
  font-size: 12px;
}

.doc__chunk-ordinal {
  color: var(--ch-brand);
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.doc__chunk-heading {
  flex: 1;
  overflow: hidden;
  color: var(--ch-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc__chunk-size {
  color: var(--ch-text-tertiary);
}

.doc__chunk-body {
  margin: 0;
  padding: 10px 12px;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.7;
}
</style>
