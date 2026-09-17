<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  fetchCategories,
  fetchPosts,
  fetchTags,
  type Category,
  type PostCard,
  type PostSort,
  type Tag,
} from '@/api/community'
import { ApiError, NetworkError } from '@/api/http'
import PostCardItem from '@/components/PostCardItem.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 社区首页：筛选 + 帖子流。
 *
 * <h2>筛选条件放在 URL 上，而不是组件状态里</h2>
 * {@code ?category=tech&tag=java&sort=hot&page=2} 可以直接分享给别人、
 * 可以刷新后保持、可以用浏览器后退回到上一个筛选 —— 这三件事在"只放组件状态"时
 * 全部丢失。代价是每次筛选都要走一次路由，而这本来也就是一次请求。
 *
 * <p>数据来源是 URL（唯一的真值），组件里的列表只是它的投影。
 * 因此不做"先改本地状态再同步 URL"的双向绑定 —— 那会引入两个真值，
 * 而它们在快速连续点击时必然出现短暂不一致。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const categories = ref<Category[]>([])
const tags = ref<Tag[]>([])
const posts = ref<PostCard[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const hasNext = ref(false)

const loading = ref(false)
const errorMessage = ref('')

const category = computed(() => (typeof route.query.category === 'string' ? route.query.category : ''))
const tag = computed(() => (typeof route.query.tag === 'string' ? route.query.tag : ''))
const sort = computed<PostSort>(() => (route.query.sort === 'hot' ? 'hot' : 'latest'))
const currentPage = computed(() => {
  const raw = Number(route.query.page)
  return Number.isFinite(raw) && raw >= 1 ? Math.floor(raw) : 1
})

const activeCategory = computed(() =>
  categories.value.find((item) => item.slug === category.value) ?? null,
)
const activeTag = computed(() => tags.value.find((item) => item.slug === tag.value) ?? null)

const hasFilter = computed(() => category.value !== '' || tag.value !== '')

/**
 * 筛选条件补丁。
 *
 * <p><b>键存在即表示要变更该项</b>，值为 {@code undefined} 表示清除该项
 * （例如点击「全部」板块）。这条约定不能用 {@code ??} 兜底实现 ——
 * 那会让「清除」悄悄变成「保持原值」，而界面上按钮已经点了。
 */
interface FeedFilterPatch {
  category?: string | undefined
  tag?: string | undefined
  sort?: PostSort | undefined
  page?: string | undefined
}

/**
 * 把筛选条件写回 URL。
 *
 * <p>合并而不是替换整个 query：这样"翻到第 2 页后点了个标签"能正确回到第 1 页。
 * 这里只负责算出「下一个 URL」，不改任何本地状态 —— URL 是真值，
 * 组件里的列表是它的投影，URL 一变就重新加载。
 *
 * @param patch 要变更的筛选条件
 */
function applyFilter(patch: FeedFilterPatch): void {
  const next: Record<string, string> = {}
  const nextCategory = 'category' in patch ? patch.category : category.value
  const nextTag = 'tag' in patch ? patch.tag : tag.value
  const nextSort = patch.sort ?? sort.value
  // 每次筛选变更都回到第 1 页。留在第 5 页看新筛选的结果是纯粹的困惑来源
  const nextPage = patch.page ?? '1'

  if (nextCategory) {
    next.category = nextCategory
  }
  if (nextTag) {
    next.tag = nextTag
  }
  // 默认排序不进 URL：`/community` 比 `/community?sort=latest` 干净，而两者等价
  if (nextSort !== 'latest') {
    next.sort = nextSort
  }
  if (nextPage !== '1') {
    next.page = nextPage
  }
  void router.push({ name: 'community', query: next })
}

/**
 * 加载帖子列表。
 */
async function loadPosts(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await fetchPosts({
      category: category.value || undefined,
      tag: tag.value || undefined,
      sort: sort.value,
      page: currentPage.value,
      size: 20,
    })
    posts.value = data.items
    total.value = data.total
    page.value = data.page
    pageSize.value = data.size
    hasNext.value = data.hasNext
  } catch (error) {
    if (error instanceof ApiError) {
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
 * 加载板块与标签入口。它们变化不频繁，只在首次进入时取一次。
 */
async function loadTaxonomy(): Promise<void> {
  try {
    const [categoryResult, tagResult] = await Promise.all([fetchCategories(), fetchTags()])
    categories.value = categoryResult.data
    tags.value = tagResult.data
  } catch {
    // 入口加载失败不影响帖子列表的可用性，因此不把它升级成页面级错误 ——
    // 那会让"标签没取到"看起来像"整个社区打不开"
  }
}

/**
 * 跳到指定页。
 *
 * @param target 目标页码
 */
function goToPage(target: number): void {
  applyFilter({ page: String(target) })
}

onMounted(() => {
  void loadTaxonomy()
  void loadPosts()
})

// 筛选条件来自 URL，因此这里只需要盯住 URL 的变化
watch(() => route.fullPath, () => {
  void loadPosts()
})
</script>

<template>
  <div class="feed">
    <section class="feed__hero">
      <div>
        <h1 class="feed__title">校园社区</h1>
        <p class="feed__desc">
          技术讨论、学习笔记、校园生活与求助问答。浏览不需要登录，参与讨论需要。
        </p>
      </div>
      <a-button
        type="primary"
        @click="auth.isLoggedIn ? router.push({ name: 'post-new' }) : router.push({ name: 'login' })"
      >
        发布帖子
      </a-button>
    </section>

    <section class="feed__filters">
      <div class="feed__filter-row">
        <span class="feed__filter-label">板块</span>
        <div class="feed__chips">
          <button
            class="feed__chip"
            :class="{ 'feed__chip--active': category === '' }"
            type="button"
            @click="applyFilter({ category: undefined })"
          >
            全部
          </button>
          <button
            v-for="item in categories"
            :key="item.slug"
            class="feed__chip"
            :class="{ 'feed__chip--active': category === item.slug }"
            type="button"
            @click="applyFilter({ category: item.slug })"
          >
            {{ item.name }}
          </button>
        </div>
      </div>

      <div class="feed__filter-row">
        <span class="feed__filter-label">标签</span>
        <div class="feed__chips">
          <button
            v-for="item in tags"
            :key="item.slug"
            class="feed__chip"
            :class="{ 'feed__chip--active': tag === item.slug }"
            type="button"
            @click="applyFilter({ tag: tag === item.slug ? undefined : item.slug })"
          >
            #{{ item.name }}
          </button>
          <span v-if="tags.length === 0" class="feed__chip-hint">暂无标签</span>
        </div>
      </div>

      <div class="feed__filter-row">
        <span class="feed__filter-label">排序</span>
        <div class="feed__chips">
          <button
            class="feed__chip"
            :class="{ 'feed__chip--active': sort === 'latest' }"
            type="button"
            @click="applyFilter({ sort: 'latest' })"
          >
            最新
          </button>
          <button
            class="feed__chip"
            :class="{ 'feed__chip--active': sort === 'hot' }"
            type="button"
            @click="applyFilter({ sort: 'hot' })"
          >
            最热
          </button>
        </div>
      </div>
    </section>

    <div v-if="hasFilter" class="feed__active-filter">
      <span>当前筛选：</span>
      <a-tag v-if="activeCategory" closable @close="applyFilter({ category: undefined })">
        {{ activeCategory.name }}
      </a-tag>
      <a-tag v-if="activeTag" closable @close="applyFilter({ tag: undefined })">
        #{{ activeTag.name }}
      </a-tag>
      <a-button size="mini" type="text" @click="applyFilter({ category: undefined, tag: undefined })">
        清除全部
      </a-button>
    </div>

    <a-alert
      v-if="errorMessage"
      class="feed__alert"
      type="error"
      :title="errorMessage"
      closable
      @close="errorMessage = ''"
    />

    <a-spin :loading="loading" class="feed__spin">
      <a-empty v-if="posts.length === 0 && !loading" description="这个筛选条件下还没有帖子" />

      <div v-else class="feed__list">
        <PostCardItem
          v-for="post in posts"
          :key="post.publicId"
          :post="post"
          @select-tag="(slug) => applyFilter({ tag: slug })"
          @select-category="(slug) => applyFilter({ category: slug })"
        />
      </div>
    </a-spin>

    <div v-if="total > 0" class="feed__pager">
      <span class="feed__pager-info">
        共 {{ total }} 条 · 第 {{ page }} 页 · 每页 {{ pageSize }} 条
      </span>
      <a-pagination
        :current="page"
        :total="total"
        :page-size="pageSize"
        :hide-on-single-page="true"
        size="small"
        @change="goToPage"
      />
      <span v-if="!hasNext" class="feed__pager-end">已经是最后一页</span>
    </div>
  </div>
</template>

<style scoped>
.feed {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.feed__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.feed__title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
}

.feed__desc {
  margin: 0;
  max-width: 640px;
  color: var(--ch-text-secondary);
}

.feed__filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.feed__filter-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.feed__filter-label {
  flex-shrink: 0;
  width: 32px;
  padding-top: 3px;
  color: var(--ch-text-tertiary);
  font-size: 13px;
}

.feed__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.feed__chip {
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--ch-bg-page);
  color: var(--ch-text-secondary);
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.feed__chip:hover {
  color: var(--ch-brand);
}

.feed__chip--active {
  background: var(--ch-brand-weak);
  color: var(--ch-brand);
  font-weight: 500;
}

.feed__chip-hint {
  color: var(--ch-text-tertiary);
  font-size: 13px;
}

.feed__active-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ch-text-secondary);
  font-size: 13px;
}

.feed__spin {
  display: block;
}

.feed__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feed__pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.feed__pager-end {
  color: var(--ch-text-tertiary);
}

@media (max-width: 720px) {
  .feed__hero {
    flex-direction: column;
  }

  .feed__filter-row {
    flex-direction: column;
    gap: 6px;
  }
}
</style>
