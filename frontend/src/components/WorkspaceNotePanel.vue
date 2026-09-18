<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { ApiError, NetworkError } from '@/api/http'
import {
  createNote,
  deleteNote,
  getNote,
  listNotes,
  updateNote,
  type NoteCard,
  type NoteDetail,
} from '@/api/workspace'
import { formatDateTime } from '@/utils/datetime'

/**
 * 空间内的协作笔记面板。
 *
 * <h2>为什么是「列表 + 详情/编辑器」而不是一页卡片墙</h2>
 * 空间的笔记是<b>被反复修改</b>的东西（这正是"协作"的含义：任何成员都能编辑任何一篇），
 * 它的主要用途不是浏览，而是"打开一篇，读或改"。卡片墙适合的是以浏览为主的社区帖子。
 * 因此这里按用途把它做成一栏列表加一块正文区：读与写都在同一位置切换。
 *
 * <h2>正文渲染沿用帖子的规则</h2>
 * {@code bodyHtml} 由服务端渲染并净化，前端直接用 {@code v-html} 插入（同
 * {@code PostDetailView}，全仓库仅这两处）。编辑时展示的是 Markdown 原文 ——
 * 原文走文本节点，永远不经过 v-html。
 */
const props = defineProps<{ publicId: string }>()

type Mode = 'view' | 'edit' | 'create'

const notes = ref<NoteCard[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const listError = ref('')

/** 当前打开的笔记 */
const selected = ref<NoteDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

/** 当前面板模式 */
const mode = ref<Mode>('view')
const saving = ref(false)
const formError = ref('')
const title = ref('')
const bodyMd = ref('')

/**
 * 加载列表。
 */
async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await listNotes(props.publicId, page.value, pageSize)
    notes.value = data.items
    total.value = data.total
  } catch (error) {
    listError.value = describe(error)
  } finally {
    loading.value = false
  }
}

/**
 * 打开一篇笔记。
 *
 * <p>打开时总是回到只读模式：上一次的编辑态不属于这一篇，
 * 把它带过来会让用户以为"这篇已经被我改过了"。
 *
 * @param notePublicId 笔记对外标识
 */
async function open(notePublicId: string): Promise<void> {
  mode.value = 'view'
  detailError.value = ''
  formError.value = ''
  detailLoading.value = true
  try {
    const { data } = await getNote(props.publicId, notePublicId)
    selected.value = data
  } catch (error) {
    selected.value = null
    detailError.value = describe(error)
  } finally {
    detailLoading.value = false
  }
}

/** 切到新建模式 */
function startCreate(): void {
  mode.value = 'create'
  selected.value = null
  title.value = ''
  bodyMd.value = ''
  detailError.value = ''
  formError.value = ''
}

/** 切到编辑模式，填入当前笔记的 Markdown 原文 */
function startEdit(): void {
  if (!selected.value) {
    return
  }
  mode.value = 'edit'
  title.value = selected.value.title
  bodyMd.value = selected.value.bodyMd
  formError.value = ''
}

/**
 * 保存（新建或更新）。
 */
async function save(): Promise<void> {
  if (title.value.trim() === '') {
    formError.value = '请填写标题'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const payload = { title: title.value.trim(), bodyMd: bodyMd.value }
    const { data } =
      mode.value === 'create'
        ? await createNote(props.publicId, payload)
        : await updateNote(props.publicId, selected.value!.publicId, payload)
    selected.value = data
    mode.value = 'view'
    await load()
  } catch (error) {
    formError.value = describe(error)
  } finally {
    saving.value = false
  }
}

/**
 * 删除当前笔记。
 */
async function remove(): Promise<void> {
  if (!selected.value) {
    return
  }
  const target = selected.value.publicId
  try {
    await deleteNote(props.publicId, target)
    selected.value = null
    mode.value = 'view'
    await load()
  } catch (error) {
    detailError.value = describe(error)
  }
}

/**
 * 翻页。
 *
 * @param next 目标页码
 */
function changePage(next: number): void {
  page.value = next
  void load()
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
  void load()
})
</script>

<template>
  <div class="notes">
    <aside class="notes__side">
      <div class="notes__side-head">
        <a-button size="mini" type="primary" @click="startCreate">新建笔记</a-button>
        <a-button size="mini" type="text" :disabled="loading" @click="load()">刷新</a-button>
      </div>

      <a-alert v-if="listError" type="error" :title="listError" closable @close="listError = ''" />

      <a-spin :loading="loading" class="notes__spin">
        <a-empty v-if="notes.length === 0 && !loading" description="还没有笔记" />
        <ul v-else class="notes__list">
          <li v-for="item in notes" :key="item.publicId">
            <button
              type="button"
              class="notes__item"
              :class="{ 'notes__item--active': selected?.publicId === item.publicId }"
              @click="open(item.publicId)"
            >
              <span class="notes__item-title">{{ item.title }}</span>
              <span class="notes__item-meta">
                {{ item.author.nickname }} · {{ formatDateTime(item.updatedAt) }}
              </span>
            </button>
          </li>
        </ul>

        <a-pagination
          v-if="total > pageSize"
          class="notes__pagination"
          size="small"
          :total="total"
          :current="page"
          :page-size="pageSize"
          @change="changePage"
        />
      </a-spin>
    </aside>

    <section class="notes__main">
      <a-alert v-if="detailError" type="error" :title="detailError" closable @close="detailError = ''" />

      <a-spin :loading="detailLoading" class="notes__spin">
        <template v-if="mode === 'create' || mode === 'edit'">
          <a-form :model="{ title, bodyMd }" layout="vertical">
            <a-form-item label="标题">
              <a-input v-model="title" :max-length="200" placeholder="笔记标题" />
            </a-form-item>
            <a-form-item label="正文（Markdown）">
              <a-textarea
                v-model="bodyMd"
                class="notes__editor"
                placeholder="支持 Markdown。提交后由服务端渲染并净化。"
                :max-length="30000"
                :auto-size="{ minRows: 14, maxRows: 26 }"
              />
            </a-form-item>
          </a-form>
          <a-alert v-if="formError" type="error" :title="formError" />
          <a-space class="notes__form-actions">
            <a-button type="primary" :loading="saving" @click="save">
              {{ mode === 'create' ? '创建' : '保存修改' }}
            </a-button>
            <a-button @click="mode = 'view'">取消</a-button>
          </a-space>
        </template>

        <template v-else-if="selected">
          <header class="notes__head">
            <h2 class="notes__title">{{ selected.title }}</h2>
            <div class="notes__meta">
              <span>{{ selected.author.nickname }} 创建</span>
              <span>最后由 {{ selected.editor.nickname }} 修改于 {{ formatDateTime(selected.updatedAt) }}</span>
            </div>
            <a-space>
              <a-button size="mini" @click="startEdit">编辑</a-button>
              <a-popconfirm
                v-if="selected.deletableByMe"
                content="删除后不可恢复。"
                type="warning"
                ok-text="删除"
                cancel-text="取消"
                @ok="remove"
              >
                <a-button size="mini" status="danger">删除</a-button>
              </a-popconfirm>
            </a-space>
          </header>

          <!-- 服务端渲染并净化的 HTML，同 PostDetailView：前端不自行渲染 Markdown -->
          <div class="notes__body markdown-body" v-html="selected.bodyHtml" />
        </template>

        <a-empty v-else description="从左侧选择一篇笔记，或者新建一篇" />
      </a-spin>
    </section>
  </div>
</template>

<style scoped>
.notes {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.notes__side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 260px;
  flex-shrink: 0;
}

.notes__side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.notes__spin {
  display: block;
}

.notes__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.notes__item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
  cursor: pointer;
  text-align: left;
}

.notes__item:hover {
  border-color: var(--ch-border);
}

.notes__item--active {
  border-color: var(--ch-brand);
  background: var(--ch-brand-weak);
}

.notes__item-title {
  overflow: hidden;
  color: var(--ch-text-primary);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notes__item-meta {
  color: var(--ch-text-tertiary);
  font-size: 11px;
}

.notes__pagination {
  margin-top: 10px;
}

.notes__main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.notes__head {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ch-border);
}

.notes__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.notes__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.notes__editor {
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

@media (max-width: 860px) {
  .notes {
    flex-direction: column;
  }

  .notes__side {
    width: 100%;
  }
}
</style>
