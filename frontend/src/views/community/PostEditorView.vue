<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { createPost, fetchCategories, fetchPost, updatePost, type Category } from '@/api/community'
import { ApiError, NetworkError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { fieldFeedback } from '@/utils/form'

/**
 * 帖子编辑器（发布与编辑共用）。
 *
 * <h2>没有客户端实时预览，这是刻意的</h2>
 * 预览意味着前端也要把 Markdown 渲染成 HTML —— 于是"净化的正确性"就从
 * 服务端一处变成了"服务端 + 每一个客户端"，而多端之后必然有某一端忘了做。
 * 因此发布前不预览，而是明确告诉用户"正文将在服务端渲染"。
 *
 * <p>代价是用户对排版没有即时反馈。这个代价是可以接受的：正文用的是标准
 * Markdown 子集（标题、列表、代码块、表格、引用），写惯了的人不需要预览；
 * 而发布后立刻跳转到详情页，看到的就是最终效果。
 *
 * <h2>编辑时先取一次详情，而不是直接用列表页传过来的数据</h2>
 * 列表项不含 {@code bodyMd}（列表页不读 MEDIUMTEXT 正文，这是刻意的）。
 * 直接开一个"从列表点进来就带着正文"的口子，会让列表查询重新变重。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const categories = ref<Category[]>([])
const categorySlug = ref('')
const title = ref('')
const bodyMd = ref('')
const tagNames = ref<string[]>([])

const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})

/** 编辑模式的帖子标识；发布模式为 null */
const editingPublicId = computed(() =>
  typeof route.params.publicId === 'string' ? route.params.publicId : null,
)
const isEditing = computed(() => editingPublicId.value !== null)

/**
 * 把后端返回的字段级错误映射到表单项上。
 *
 * <p>项目约定：字段格式问题（少填、超长）走 40000 且带 {@code details}，
 * 业务策略问题（标签过多、正文超上限）走 40031 且只有一句 message。
 * 两者都要能展示出来，否则用户只知道"失败了"。
 *
 * @param error 异常
 */
function applyError(error: unknown): void {
  fieldErrors.value = {}
  if (error instanceof ApiError) {
    errorMessage.value = error.message
    // 后端 details 里的 field 是 record 组件名（title / bodyMd / categorySlug / tagNames），
    // 与这里表单字段同名，因此可以直接对应
    fieldErrors.value = error.fieldMessages()
  } else if (error instanceof NetworkError) {
    errorMessage.value = `${error.message}。请确认后端已启动（默认 127.0.0.1:8080）。`
  } else {
    errorMessage.value = '发生未知错误'
  }
}

/**
 * 初始化：载入板块列表；编辑模式下再载入原帖内容。
 */
async function initialize(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await fetchCategories()
    categories.value = data

    if (isEditing.value && editingPublicId.value) {
      const post = await fetchPost(editingPublicId.value)
      if (!post.data.ownedByMe) {
        // 不是作者就没有编辑入口。这里给一句明确的话，而不是让用户提交后收到 404
        errorMessage.value = '只有作者本人可以编辑这条帖子。'
        return
      }
      categorySlug.value = post.data.categorySlug
      title.value = post.data.title
      bodyMd.value = post.data.bodyMd
      tagNames.value = post.data.tags.map((tag) => tag.name)
    } else if (data.length > 0) {
      categorySlug.value = data[0]!.slug
    }
  } catch (error) {
    applyError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 提交。
 */
async function submit(): Promise<void> {
  submitting.value = true
  errorMessage.value = ''
  fieldErrors.value = {}
  const payload = {
    categorySlug: categorySlug.value,
    title: title.value,
    bodyMd: bodyMd.value,
    tagNames: tagNames.value,
  }
  try {
    const result =
      isEditing.value && editingPublicId.value
        ? await updatePost(editingPublicId.value, payload)
        : await createPost(payload)
    void router.push({ name: 'post-detail', params: { publicId: result.data.publicId } })
  } catch (error) {
    applyError(error)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  void initialize()
})
</script>

<template>
  <div class="editor">
    <header class="editor__head">
      <h1 class="editor__title">{{ isEditing ? '编辑帖子' : '发布帖子' }}</h1>
      <p class="editor__desc">
        正文使用 Markdown（支持标题、列表、代码块、表格、引用）。渲染与内容净化在服务端完成，
        因此这里不提供实时预览 —— 发布后看到的就是最终效果。
      </p>
    </header>

    <a-alert
      v-if="errorMessage"
      class="editor__alert"
      type="error"
      :title="errorMessage"
      closable
      @close="errorMessage = ''"
    />

    <a-spin :loading="loading" class="editor__spin">
      <a-form :model="{ categorySlug, title, bodyMd, tagNames }" layout="vertical">
        <a-form-item field="categorySlug" label="板块" v-bind="fieldFeedback(fieldErrors, 'categorySlug')">
          <a-select v-model="categorySlug" placeholder="选择板块" allow-clear>
            <a-option v-for="item in categories" :key="item.slug" :value="item.slug">
              {{ item.name }}
              <span class="editor__option-desc">{{ item.description }}</span>
            </a-option>
          </a-select>
        </a-form-item>

        <a-form-item field="title" label="标题" v-bind="fieldFeedback(fieldErrors, 'title')">
          <a-input v-model="title" placeholder="一句话说清这篇帖子讲什么" :max-length="120" show-word-limit />
        </a-form-item>

        <a-form-item
          field="tagNames"
          label="标签"
          extra="最多 5 个。回车添加；不存在的标签会被自动创建。"
          v-bind="fieldFeedback(fieldErrors, 'tagNames')"
        >
          <a-input-tag v-model="tagNames" :max-tag-count="5" placeholder="例如：Java、期末复习" />
        </a-form-item>

        <a-form-item field="bodyMd" label="正文（Markdown）" v-bind="fieldFeedback(fieldErrors, 'bodyMd')">
          <a-textarea
            v-model="bodyMd"
            placeholder="支持 Markdown。代码块用三个反引号包裹，表格用竖线分隔。"
            :auto-size="{ minRows: 14, maxRows: 30 }"
          />
        </a-form-item>

        <div class="editor__actions">
          <a-button type="primary" :loading="submitting" @click="submit">
            {{ isEditing ? '保存修改' : '发布' }}
          </a-button>
          <a-button @click="router.back()">取消</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.editor__title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
}

.editor__desc {
  margin: 0;
  max-width: 720px;
  color: var(--ch-text-secondary);
}

.editor__alert {
  margin-bottom: 4px;
}

.editor__spin {
  display: block;
}

.editor__option-desc {
  margin-left: 8px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.editor__actions {
  display: flex;
  gap: 10px;
  padding-top: 8px;
}
</style>
