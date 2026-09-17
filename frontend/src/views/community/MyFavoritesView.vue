<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchMyFavorites, type PostCard } from '@/api/community'
import { ApiError, NetworkError } from '@/api/http'
import PostCardItem from '@/components/PostCardItem.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 我的收藏。
 *
 * <p>收藏是账号级数据，接口需要登录。未登录时页面不去发那个必然 401 的请求，
 * 而是直接给出登录入口 —— 让用户先看到一个错误提示再告诉他去登录，
 * 是把"需要登录"这件已知事实当成了一次故障来呈现。
 */
const router = useRouter()
const auth = useAuthStore()

const posts = ref<PostCard[]>([])
const loading = ref(false)
const errorMessage = ref('')
const total = ref(0)

/**
 * 加载收藏列表。
 */
async function load(): Promise<void> {
  if (!auth.isLoggedIn) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await fetchMyFavorites(1, 50)
    posts.value = data.items
    total.value = data.total
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
    } else if (error instanceof NetworkError) {
      errorMessage.value = `${error.message}。请确认后端已启动。`
    } else {
      errorMessage.value = '发生未知错误'
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="favorites">
    <header class="favorites__head">
      <h1 class="favorites__title">我的收藏</h1>
      <p class="favorites__desc">按收藏时间倒序。取消收藏后这里会立即消失。</p>
    </header>

    <a-alert
      v-if="errorMessage"
      class="favorites__alert"
      type="error"
      :title="errorMessage"
      closable
      @close="errorMessage = ''"
    />

    <a-result
      v-if="!auth.isLoggedIn"
      status="403"
      title="需要登录"
      subtitle="收藏是账号级数据，登录后才能查看。"
    >
      <template #extra>
        <a-button type="primary" @click="$router.push({ name: 'login', query: { redirect: '/community/favorites' } })">
          去登录
        </a-button>
      </template>
    </a-result>

    <a-spin v-else :loading="loading" class="favorites__spin">
      <a-empty v-if="posts.length === 0 && !loading" description="还没有收藏过任何帖子" />
      <div v-else class="favorites__list">
        <p class="favorites__count">共 {{ total }} 条</p>
        <PostCardItem
          v-for="post in posts"
          :key="post.publicId"
          :post="post"
          @select-tag="(slug) => router.push({ name: 'community', query: { tag: slug } })"
          @select-category="(slug) => router.push({ name: 'community', query: { category: slug } })"
        />
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.favorites {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.favorites__title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
}

.favorites__desc {
  margin: 0;
  color: var(--ch-text-secondary);
}

.favorites__spin {
  display: block;
}

.favorites__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.favorites__count {
  margin: 0;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}
</style>
