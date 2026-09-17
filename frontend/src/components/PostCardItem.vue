<script setup lang="ts">
import { RouterLink } from 'vue-router'

import type { PostCard } from '@/api/community'
import { formatDateTime } from '@/utils/datetime'

/**
 * 帖子列表项。
 *
 * <h2>摘要用文本节点渲染，不用 v-html</h2>
 * 摘要来自服务端的纯文本提取，本身不含标记。即便如此也不使用 `v-html` ——
 * 一个组件里存在 `v-html` 就会成为后来者"顺手也把正文塞进来"的理由，
 * 而正文的 HTML 只能由服务端产出。这里保持"整棵树没有任何 v-html"这一事实，
 * 让审查时只需要确认一件事：全仓库的 v-html 只在详情页的 bodyHtml 那一处。
 */
const props = defineProps<{
  post: PostCard
}>()

const emit = defineEmits<{
  /** 请求打开某个标签 */
  (e: 'select-tag', slug: string): void
  /** 请求打开某个板块 */
  (e: 'select-category', slug: string): void
}>()
</script>

<template>
  <article class="post-card">
    <div class="post-card__head">
      <RouterLink class="post-card__title" :to="`/community/posts/${props.post.publicId}`">
        {{ props.post.title }}
      </RouterLink>
      <a-tag v-if="props.post.ownedByMe" size="small" color="arcoblue">我发布的</a-tag>
    </div>

    <p class="post-card__summary">{{ props.post.summary }}</p>

    <div class="post-card__meta">
      <button
        class="post-card__category"
        type="button"
        @click="emit('select-category', props.post.categorySlug)"
      >
        {{ props.post.categoryName }}
      </button>

      <span class="post-card__author">{{ props.post.author.nickname }}</span>
      <span class="post-card__time">{{ formatDateTime(props.post.publishedAt) }}</span>

      <button
        v-for="tag in props.post.tags"
        :key="tag.slug"
        class="post-card__tag"
        type="button"
        @click="emit('select-tag', tag.slug)"
      >
        #{{ tag.name }}
      </button>
    </div>

    <div class="post-card__stats">
      <span :class="{ 'post-card__stat--on': props.post.liked }">
        <span class="post-card__icon">👍</span>{{ props.post.likeCount }}
      </span>
      <span :class="{ 'post-card__stat--on': props.post.favorited }">
        <span class="post-card__icon">⭐</span>{{ props.post.favoriteCount }}
      </span>
      <span>
        <span class="post-card__icon">💬</span>{{ props.post.commentCount }}
      </span>
      <span>
        <span class="post-card__icon">👁</span>{{ props.post.viewCount }}
      </span>
    </div>
  </article>
</template>

<style scoped>
.post-card {
  padding: 16px 18px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
  transition: border-color 0.15s ease;
}

.post-card:hover {
  border-color: var(--ch-brand-hover);
}

.post-card__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.post-card__title {
  color: var(--ch-text-primary);
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
}

.post-card__title:hover {
  color: var(--ch-brand);
}

.post-card__summary {
  margin: 8px 0 10px;
  color: var(--ch-text-secondary);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.post-card__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.post-card__category {
  padding: 1px 8px;
  border: none;
  border-radius: 999px;
  background: var(--ch-brand-weak);
  color: var(--ch-brand);
  cursor: pointer;
  font-size: 12px;
}

.post-card__author {
  color: var(--ch-text-secondary);
}

.post-card__tag {
  padding: 0;
  border: none;
  background: none;
  color: var(--ch-text-tertiary);
  cursor: pointer;
  font-size: 12px;
}

.post-card__tag:hover {
  color: var(--ch-brand);
}

.post-card__stats {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.post-card__stat--on {
  color: var(--ch-brand);
  font-weight: 500;
}

.post-card__icon {
  margin-right: 3px;
}
</style>
